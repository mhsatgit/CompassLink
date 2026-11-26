using System.IO.Pipes;
using System.Text;
using HIDMaestro;

namespace CompassLink;

internal static class KeyboardHost
{
    private static readonly TimeSpan Lease = TimeSpan.FromSeconds(3);

    internal static async Task Run(string owner, HMContext context, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            using var pipe = LocalPipe.Open(owner, "Keyboard");
            try { await pipe.WaitForConnectionAsync(stop); }
            catch (OperationCanceledException) { break; }
            try { await Serve(pipe, context, stop); }
            catch (EndOfStreamException) { }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
            catch (Exception error)
            {
                Console.Error.WriteLine(error.Message);
                try { await Reply(pipe, "error " + error.Message, stop); }
                catch (IOException) { }
                catch (OperationCanceledException) { }
            }
        }
    }

    private static async Task Serve(NamedPipeServerStream pipe, HMContext context, CancellationToken stop)
    {
        var command = new byte[1];
        await Read(pipe, command, stop);
        if (command[0] != 0) throw new InvalidDataException("Expected a virtual keyboard connection request.");
        using var keyboard = await VirtualKeyboard.Attach(context, stop);
        await Reply(pipe, "ready", stop);
        while (!stop.IsCancellationRequested)
        {
            await Read(pipe, command, stop);
            switch (command[0])
            {
                case 1:
                case 2:
                    bool down = command[0] == 1;
                    await Read(pipe, command, stop);
                    keyboard.SetKey(command[0], down);
                    break;
                case 3:
                    break;
                case 4:
                    keyboard.Clear();
                    await Reply(pipe, "reset", stop);
                    break;
                default:
                    throw new InvalidDataException("Unknown virtual keyboard command.");
            }
        }
    }

    private static async Task Read(Stream pipe, byte[] command, CancellationToken stop)
    {
        using var lease = CancellationTokenSource.CreateLinkedTokenSource(stop);
        lease.CancelAfter(Lease);
        await pipe.ReadExactlyAsync(command, lease.Token);
    }

    private static async Task Reply(Stream pipe, string message, CancellationToken stop)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(stop);
        deadline.CancelAfter(Lease);
        await pipe.WriteAsync(Encoding.UTF8.GetBytes(message + "\n"), deadline.Token);
    }
}
