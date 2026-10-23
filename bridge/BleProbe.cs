// Sonde BLE (diagnostic) : scan des annonces, puis GATT RT-660 et requete GET_DEV_INFO.
using System;
using System.Linq;
using Windows.Foundation;
using Windows.Security.Cryptography;
using System.Threading;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;

static class BleProbe
{
    static readonly Guid Svc = new Guid("00001100-d102-11e1-9b23-00025b00a5a5");
    static readonly Guid Wr = new Guid("00001101-d102-11e1-9b23-00025b00a5a5");
    static readonly Guid Ind = new Guid("00001102-d102-11e1-9b23-00025b00a5a5");

    static byte[] Arr(Windows.Storage.Streams.IBuffer b) { byte[] r; CryptographicBuffer.CopyToByteArray(b, out r); return r ?? new byte[0]; }

    static T Wait<T>(this IAsyncOperation<T> op)
    {
        while (op.Status == AsyncStatus.Started) Thread.Sleep(5);
        if (op.Status != AsyncStatus.Completed) throw new Exception("WinRT : " + op.Status + (op.ErrorCode != null ? " " + op.ErrorCode.Message : ""));
        return op.GetResults();
    }

    static string Hex(byte[] b) { return string.Join(" ", b.Select(x => x.ToString("X2"))); }

    static int Main(string[] args)
    {
        ulong addr = Convert.ToUInt64(args[0].Replace(":", ""), 16);
        Console.WriteLine("Scan BLE 6 s...");
        var w = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
        w.Received += (s, e) =>
        {
            string n = e.Advertisement.LocalName;
            if (!string.IsNullOrEmpty(n)) Console.WriteLine("  annonce " + e.BluetoothAddress.ToString("X12") + " '" + n + "' rssi " + e.RawSignalStrengthInDBm);
        };
        w.Start();
        Thread.Sleep(6000);
        w.Stop();

        Console.WriteLine("GATT sur " + args[0]);
        var dev = BluetoothLEDevice.FromBluetoothAddressAsync(addr).Wait();
        if (dev == null) { Console.WriteLine("  pas d'appareil BLE a cette adresse"); return 1; }
        Console.WriteLine("  appareil : " + dev.Name + " etat " + dev.ConnectionStatus);
        var sr = dev.GetGattServicesForUuidAsync(Svc, BluetoothCacheMode.Uncached).Wait();
        Console.WriteLine("  services : " + sr.Status + " " + sr.Services.Count);
        if (sr.Services.Count == 0) return 1;
        var svc = sr.Services[0];
        var cw = svc.GetCharacteristicsForUuidAsync(Wr, BluetoothCacheMode.Uncached).Wait().Characteristics[0];
        var ci = svc.GetCharacteristicsForUuidAsync(Ind, BluetoothCacheMode.Uncached).Wait().Characteristics[0];
        ci.ValueChanged += (c, e) => Console.WriteLine("  <- " + Hex(Arr(e.CharacteristicValue)));
        var st = ci.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Indicate).Wait();
        Console.WriteLine("  indications : " + st);
        byte[] req = { 0x00, 0x02, 0x00, 0x04, 0x03 };
        var ws = cw.WriteValueAsync(CryptographicBuffer.CreateFromByteArray(req), GattWriteOption.WriteWithResponse).Wait();
        Console.WriteLine("  -> " + Hex(req) + " : " + ws);
        Thread.Sleep(3000);
        dev.Dispose();
        return 0;
    }
}
