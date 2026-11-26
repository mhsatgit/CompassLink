using System.ComponentModel;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace CompassLink;

internal static class LocalPipe
{
    private const uint Duplex = 0x3;
    private const uint Overlapped = 0x40000000;
    private const uint FirstInstance = 0x80000;
    private const uint RejectRemoteClients = 0x8;

    internal static NamedPipeServerStream Open(string owner, string device = "Xbox")
    {
        var acl = $"D:P(A;;GA;;;{owner})(A;;GA;;;SY)";
        if (!ConvertStringSecurityDescriptorToSecurityDescriptor(acl, 1, out var descriptor, out _))
            throw new Win32Exception(Marshal.GetLastWin32Error());

        try
        {
            var security = new SecurityAttributes
            {
                Length = Marshal.SizeOf<SecurityAttributes>(),
                Descriptor = descriptor
            };
            var handle = CreateNamedPipe($@"\\.\pipe\CompassLink.{device}.{owner}",
                Duplex | Overlapped | FirstInstance, RejectRemoteClients, 1, 4096, 4096, 0, ref security);
            if (handle.IsInvalid)
            {
                var error = Marshal.GetLastWin32Error();
                handle.Dispose();
                throw new Win32Exception(error);
            }
            return new NamedPipeServerStream(PipeDirection.InOut, isAsync: true, isConnected: false, handle);
        }
        finally { LocalFree(descriptor); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        public int Length;
        public IntPtr Descriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(
        string descriptor, uint revision, out IntPtr security, out uint size);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafePipeHandle CreateNamedPipe(string name, uint access, uint mode,
        uint instances, uint outgoing, uint incoming, uint timeout, ref SecurityAttributes security);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr address);
}
