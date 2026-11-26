using System;
using System.Runtime.InteropServices;

namespace CompassLink;

internal static class XInput
{
    internal const uint Success = 0;
    internal const uint Disconnected = 1167;

    internal static uint AttachedSlots()
    {
        uint mask = 0;
        for (uint slot = 0; slot < 4; slot++)
        {
            uint result = Read(slot, out _);
            if (result == Success) mask |= 1u << (int)slot;
            else if (result != Disconnected)
                throw new InvalidOperationException($"XInput slot {slot + 1} returned error {result}.");
        }
        return mask;
    }

    internal static uint Read(uint slot, out Pad pad)
    {
        uint result = XInputGetState(slot, out var state);
        pad = state.Gamepad;
        return result;
    }

    [DllImport("xinput1_4.dll", ExactSpelling = true)]
    private static extern uint XInputGetState(uint userIndex, out State state);

    [StructLayout(LayoutKind.Sequential)]
    private struct State
    {
        public uint Number;
        public Pad Gamepad;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct Pad
    {
        public ushort Buttons;
        public byte LeftTrigger, RightTrigger;
        public short LeftX, LeftY, RightX, RightY;

        public readonly bool IsCentered => Math.Abs((int)LeftX) <= 1 && Math.Abs((int)LeftY) <= 1 &&
            Math.Abs((int)RightX) <= 1 && Math.Abs((int)RightY) <= 1;
        public readonly bool IsNeutral => IsCentered && Buttons == 0 && LeftTrigger == 0 && RightTrigger == 0;

        public override readonly string ToString() =>
            $"buttons=0x{Buttons:X4}; LX={LeftX}; LY={LeftY}; RX={RightX}; RY={RightY}; LT={LeftTrigger}; RT={RightTrigger}";
    }
}
