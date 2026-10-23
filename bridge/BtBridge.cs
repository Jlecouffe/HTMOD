/*
 * HTMOD Windows : pont Bluetooth RFCOMM entre le poste (Benshi / Vero / BTech / Radtel) et l'appli Java.
 *
 * Java n'a pas d'acces Bluetooth natif sous Windows ; ce petit programme (.NET Framework 4, compile
 * avec le csc.exe livre avec Windows) ouvre les deux canaux du poste :
 *   - canal 0, commandes : BLE GATT (service 00001100-d102-11e1-9b23-00025b00a5a5, comme Radtel
 *     Tools pour le RT-660 : messages bruts, une indication = un message), sinon SPP RFCOMM
 *     (0x1101, messages enveloppes en GAIA FF 01, comme HTCommander) ;
 *   - canal 1, audio : RFCOMM, service "BS AOC" (39144315-32FA-40DB-85ED-FBFEBA2D86E6), audio SBC ;
 *     si la recherche SDP echoue, on sonde les canaux RFCOMM 1 a 12.
 *
 * Usage :
 *   btbridge list               -> une ligne "MAC<TAB>nom" par appareil appaire
 *   btbridge connect <MAC>      -> relais binaire sur stdin/stdout
 *
 * Trames (dans les deux sens) : [canal:1][longueur:4, big-endian][donnees].
 * Canal 2 (sortie seulement) : evenement texte UTF-8 ("connected 0 ble", "connected 0 gaia",
 * "connected 1", "closed 1", "error ...").
 */
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Enumeration;
using Windows.Foundation;
using Windows.Security.Cryptography;

static class BtBridge
{
    static readonly Guid BleService = new Guid("00001100-d102-11e1-9b23-00025b00a5a5");
    static readonly Guid BleWrite = new Guid("00001101-d102-11e1-9b23-00025b00a5a5");
    static readonly Guid BleIndicate = new Guid("00001102-d102-11e1-9b23-00025b00a5a5");

    static BluetoothLEDevice bleDevice;
    static GattCharacteristic bleWriteChar, bleIndChar;

    const int AF_BTH = 32;
    const int BTHPROTO_RFCOMM = 3;

    static readonly Guid SppUuid = new Guid("00001101-0000-1000-8000-00805F9B34FB");
    static readonly Guid BsAocUuid = new Guid("39144315-32FA-40DB-85ED-FBFEBA2D86E6");
    static readonly Guid GenericAudioUuid = new Guid("00001203-0000-1000-8000-00805F9B34FB");

    static readonly object OutLock = new object();
    static Stream stdout;
    static readonly Socket[] Socks = new Socket[2];

    /**
     * Regle entrante du pare-feu Windows pour le serveur web de HTMod, a la demande de l'utilisateur :
     * netsh.exe (outil standard de Windows) lance avec la demande d'elevation habituelle (UAC).
     * Pas de PowerShell, pas de commande encodee, pas de fenetre cachee.
     */
    static int Firewall(string exe)
    {
        string rule = "HTMod serveur web";
        int code = RunNetsh("advfirewall firewall add rule name=\"" + rule + "\" dir=in action=allow program=\"" + exe
            + "\" enable=yes profile=private,domain", true);
        Console.WriteLine(code == 0 ? "OK" : "ECHEC " + code);
        return code;
    }

    static int RunNetsh(string arguments, bool elevate)
    {
        var psi = new System.Diagnostics.ProcessStartInfo("netsh.exe", arguments);
        psi.UseShellExecute = true;
        if (elevate) psi.Verb = "runas";
        psi.WindowStyle = System.Diagnostics.ProcessWindowStyle.Minimized;
        try
        {
            using (var p = System.Diagnostics.Process.Start(psi))
            {
                p.WaitForExit();
                return p.ExitCode;
            }
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return 1223; // autorisation refusee par l'utilisateur
        }
    }

    static int Main(string[] args)
    {
        try
        {
            if (args.Length >= 1 && args[0] == "list") return List();
            if (args.Length >= 2 && args[0] == "connect") return Connect(ParseMac(args[1]));
            if (args.Length >= 2 && args[0] == "firewall") return Firewall(args[1]);
            Console.Error.WriteLine("usage : btbridge list | btbridge connect AA:BB:CC:DD:EE:FF | btbridge firewall <chemin de HTMod.exe>");
            return 2;
        }
        catch (Exception e)
        {
            Console.Error.WriteLine(e.Message);
            return 1;
        }
    }

    // ------------------------------------------------------------------ Liste des appareils appaires

    [StructLayout(LayoutKind.Sequential)]
    struct SYSTEMTIME { public ushort y, mo, dow, d, h, mi, s, ms; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct BLUETOOTH_DEVICE_INFO
    {
        public int dwSize;
        public ulong Address;
        public uint ulClassofDevice;
        public int fConnected, fRemembered, fAuthenticated;
        public SYSTEMTIME stLastSeen, stLastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string szName;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct BLUETOOTH_DEVICE_SEARCH_PARAMS
    {
        public int dwSize;
        public int fReturnAuthenticated, fReturnRemembered, fReturnUnknown, fReturnConnected, fIssueInquiry;
        public byte cTimeoutMultiplier;
        public IntPtr hRadio;
    }

    [DllImport("bthprops.cpl", SetLastError = true)]
    static extern IntPtr BluetoothFindFirstDevice(ref BLUETOOTH_DEVICE_SEARCH_PARAMS p, ref BLUETOOTH_DEVICE_INFO info);

    [DllImport("bthprops.cpl", SetLastError = true)]
    static extern bool BluetoothFindNextDevice(IntPtr h, ref BLUETOOTH_DEVICE_INFO info);

    [DllImport("bthprops.cpl", SetLastError = true)]
    static extern bool BluetoothFindDeviceClose(IntPtr h);

    static int List()
    {
        var p = new BLUETOOTH_DEVICE_SEARCH_PARAMS();
        p.dwSize = Marshal.SizeOf(typeof(BLUETOOTH_DEVICE_SEARCH_PARAMS));
        p.fReturnAuthenticated = 1;
        p.fReturnRemembered = 1;
        p.fReturnConnected = 1;
        var info = new BLUETOOTH_DEVICE_INFO();
        info.dwSize = Marshal.SizeOf(typeof(BLUETOOTH_DEVICE_INFO));
        IntPtr h = BluetoothFindFirstDevice(ref p, ref info);
        if (h == IntPtr.Zero) return 0; // aucun appareil (ou Bluetooth desactive)
        var seen = new HashSet<ulong>();
        var w = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
        do
        {
            if (seen.Add(info.Address)) w.WriteLine(FormatMac(info.Address) + "\t" + info.szName);
            info.dwSize = Marshal.SizeOf(typeof(BLUETOOTH_DEVICE_INFO));
        } while (BluetoothFindNextDevice(h, ref info));
        BluetoothFindDeviceClose(h);
        w.Flush();
        return 0;
    }

    // ------------------------------------------------------------------ Connexion RFCOMM

    /// <summary>SOCKADDR_BTH (packe, 30 octets) : famille, adresse, UUID de service, port RFCOMM.</summary>
    sealed class BthEndPoint : EndPoint
    {
        readonly ulong addr;
        readonly Guid service;
        readonly int port;

        public BthEndPoint(ulong addr, Guid service, int port) { this.addr = addr; this.service = service; this.port = port; }

        public override AddressFamily AddressFamily { get { return (AddressFamily)AF_BTH; } }

        public override SocketAddress Serialize()
        {
            var sa = new SocketAddress((AddressFamily)AF_BTH, 30);
            for (int i = 0; i < 8; i++) sa[2 + i] = (byte)(addr >> (8 * i));
            byte[] g = service.ToByteArray(); // meme disposition que la structure GUID Win32
            for (int i = 0; i < 16; i++) sa[10 + i] = g[i];
            for (int i = 0; i < 4; i++) sa[26 + i] = (byte)(port >> (8 * i));
            return sa;
        }

        public override EndPoint Create(SocketAddress sa) { return this; }
    }

    static Socket Open(ulong addr, Guid[] services, int[] fallbackPorts)
    {
        Exception last = null;
        foreach (Guid g in services)
        {
            try { return OpenOne(new BthEndPoint(addr, g, 0)); }
            catch (Exception e) { last = e; }
        }
        // Recherche SDP en echec (peu fiable sur ces postes) : on sonde les numeros de canal.
        foreach (int port in fallbackPorts)
        {
            try { return OpenOne(new BthEndPoint(addr, Guid.Empty, port)); }
            catch (Exception e) { last = e; }
        }
        throw last;
    }

    // ------------------------------------------------------------------ BLE GATT (RT-660)

    static T Wait<T>(IAsyncOperation<T> op, int timeoutMs)
    {
        int waited = 0;
        while (op.Status == AsyncStatus.Started)
        {
            if (waited > timeoutMs) { op.Cancel(); throw new TimeoutException("delai depasse"); }
            Thread.Sleep(5);
            waited += 5;
        }
        if (op.Status != AsyncStatus.Completed)
            throw new WinRtException(op.ErrorCode);
        return op.GetResults();
    }

    sealed class WinRtException : Exception
    {
        public readonly int Code;

        public WinRtException(Exception inner)
            : base(inner != null ? inner.Message + " (0x" + inner.HResult.ToString("X8") + ")" : "operation WinRT annulee")
        {
            Code = inner != null ? inner.HResult : 0;
        }
    }

    static GattSession bleSession;
    static volatile bool bleSecuring;
    static int bleLevel;          // 0 = sans exigence, 1 = chiffre, 2 = chiffre + authentifie
    static bool bleRepaired;      // reappairage complet deja tente

    /** Ouvre le service de commandes BLE ; renvoie false si le poste n'a pas ce service. */
    static bool OpenBle(ulong addr, out string why)
    {
        why = "";
        try
        {
            bleDevice = Wait(BluetoothLEDevice.FromBluetoothAddressAsync(addr), 10000);
            if (bleDevice == null) { why = "pas d'appareil BLE a cette adresse"; return false; }
            // Sans session maintenue, Windows relache le lien BLE (=> "Unreachable") et ne le
            // retablit pas apres un appairage ; avec, il se reconnecte tout seul.
            try
            {
                bleSession = Wait(GattSession.FromDeviceIdAsync(bleDevice.BluetoothDeviceId), 10000);
                bleSession.MaintainConnection = true;
            }
            catch (Exception e) { Event("info session GATT : " + e.Message); }

            GattDeviceServicesResult sr = null;
            for (int i = 0; i < 4; i++) // le premier essai echoue souvent pendant l'etablissement du lien
            {
                sr = Wait(bleDevice.GetGattServicesForUuidAsync(BleService, i < 2 ? BluetoothCacheMode.Uncached : BluetoothCacheMode.Cached), 15000);
                if (sr.Status == GattCommunicationStatus.Success && sr.Services.Count > 0) break;
                Thread.Sleep(800);
            }
            if (sr == null || sr.Services.Count == 0) { why = "service BLE absent (" + (sr == null ? "?" : sr.Status.ToString()) + ")"; return false; }
            GattDeviceService svc = sr.Services[0];
            try
            {
                GattOpenStatus os = Wait(svc.OpenAsync(GattSharingMode.SharedReadAndWrite), 10000);
                if (os != GattOpenStatus.Success && os != GattOpenStatus.AlreadyOpened) Event("info ouverture du service : " + os);
            }
            catch (Exception e) { Event("info ouverture du service : " + e.Message); }

            GattCharacteristicsResult cw = null, ci = null;
            for (int i = 0; i < 4; i++)
            {
                BluetoothCacheMode m = i < 2 ? BluetoothCacheMode.Uncached : BluetoothCacheMode.Cached;
                cw = Wait(svc.GetCharacteristicsForUuidAsync(BleWrite, m), 10000);
                ci = Wait(svc.GetCharacteristicsForUuidAsync(BleIndicate, m), 10000);
                if (cw.Characteristics.Count > 0 && ci.Characteristics.Count > 0) break;
                Thread.Sleep(800);
            }
            if (cw.Characteristics.Count == 0 || ci.Characteristics.Count == 0)
            { why = "caracteristiques BLE absentes (" + cw.Status + "/" + ci.Status + ")"; return false; }
            bleWriteChar = cw.Characteristics[0];
            bleIndChar = ci.Characteristics[0];
            bleIndChar.ValueChanged += (c, e) =>
            {
                byte[] b;
                CryptographicBuffer.CopyToByteArray(e.CharacteristicValue, out b);
                if (b != null && b.Length > 0) Write(0, b, b.Length);
            };
            if (!Subscribe(out why)) return false;

            bleDevice.ConnectionStatusChanged += (d, o) =>
            {
                if (d.ConnectionStatus != BluetoothConnectionStatus.Disconnected || bleSecuring) return;
                // la session maintenue relance la connexion : on ne quitte que si elle ne revient pas
                for (int i = 0; i < 20; i++)
                {
                    Thread.Sleep(500);
                    if (d.ConnectionStatus == BluetoothConnectionStatus.Connected || bleSecuring) return;
                }
                Event("closed 0");
                Environment.Exit(0);
            };
            return true;
        }
        catch (Exception e)
        {
            why = e.Message;
            return false;
        }
    }

    /** Abonnement aux indications du poste (une indication = un message complet). */
    static bool Subscribe(out string why)
    {
        why = "";
        var cccd = (bleIndChar.CharacteristicProperties & GattCharacteristicProperties.Indicate) != 0
            ? GattClientCharacteristicConfigurationDescriptorValue.Indicate
            : GattClientCharacteristicConfigurationDescriptorValue.Notify;
        GattCommunicationStatus st = GattCommunicationStatus.Unreachable;
        for (int i = 1; i <= 5; i++) // l'etablissement du lien prend parfois quelques secondes
        {
            try { st = Wait(bleIndChar.WriteClientCharacteristicConfigurationDescriptorAsync(cccd), 10000); }
            catch (Exception e) { why = e.Message; st = GattCommunicationStatus.Unreachable; }
            if (st == GattCommunicationStatus.Success) return true;
            WaitConnected(4000);
        }
        why = "abonnement aux indications refuse (" + st + (why.Length > 0 ? ", " + why : "") + ")";
        return false;
    }

    static void WaitConnected(int ms)
    {
        for (int t = 0; t < ms; t += 250)
        {
            try { if (bleDevice.ConnectionStatus == BluetoothConnectionStatus.Connected) { Thread.Sleep(250); return; } } catch { }
            Thread.Sleep(250);
        }
    }

    static readonly object BleWriteLock = new object();

    static bool IsSecurityError(int code)
    {
        uint c = (uint)code; // 0x80650005 authentification, 0x80650008 autorisation, 0x8065000C taille de cle, 0x8065000F chiffrement
        return c == 0x80650005u || c == 0x80650008u || c == 0x8065000Cu || c == 0x8065000Fu;
    }

    static void BleSend(byte[] data)
    {
        lock (BleWriteLock)
        {
            string last = "";
            int unreachableEncrypted = 0;
            for (int attempt = 0; attempt < 8; attempt++)
            {
                bool security = false, unreachable = false;
                try
                {
                    var st = Wait(bleWriteChar.WriteValueAsync(CryptographicBuffer.CreateFromByteArray(data), GattWriteOption.WriteWithResponse), 10000);
                    if (st == GattCommunicationStatus.Success) return;
                    last = st.ToString();
                    if (st == GattCommunicationStatus.Unreachable) unreachable = true;
                    else security = true; // AccessDenied / ProtocolError
                }
                catch (WinRtException e)
                {
                    last = e.Message;
                    if (IsSecurityError(e.Code)) security = true;
                    else unreachable = true;
                }
                if (security)
                {
                    if (!Escalate()) break;
                }
                else if (unreachable)
                {
                    // Lien perdu des que le chiffrement est impose : le poste refuse la cle BLE
                    // gardee par Windows (il a re-appaire un autre appareil). Reappairage complet.
                    if (bleLevel >= 1 && !bleRepaired && ++unreachableEncrypted >= 2)
                    {
                        bleLevel = 2;
                        if (!Escalate()) break;
                        unreachableEncrypted = 0;
                        continue;
                    }
                    WaitConnected(5000);
                }
            }
            Event("error envoi BLE : " + last);
        }
    }

    /** Le poste exige plus de securite : appairage BLE puis chiffrement, chiffrement authentifie, enfin reappairage. */
    static bool Escalate()
    {
        bleSecuring = true;
        try
        {
            DeviceInformationPairing pairing = bleDevice.DeviceInformation.Pairing;
            if (bleLevel == 0)
            {
                Event("info le poste exige un lien chiffré ; appairé en BLE : " + (pairing.IsPaired ? "oui" : "non"));
                if (!pairing.IsPaired) Pair(pairing);
                SetLevel(GattProtectionLevel.EncryptionRequired);
                bleLevel = 1;
            }
            else if (bleLevel == 1)
            {
                Event("info chiffrement authentifié demandé");
                SetLevel(GattProtectionLevel.EncryptionAndAuthenticationRequired);
                bleLevel = 2;
            }
            else
            {
                // Le poste refuse la cle BLE de Windows (lien coupe des que le chiffrement est impose).
                // Pas de desappairage automatique : le poste coupe alors la liaison et le reappairage echoue.
                bleRepaired = true;
                Event("error securite BLE : le poste refuse la clé d'appairage de Windows. Supprimez le RT-660 dans "
                    + "Paramètres Windows > Bluetooth, mettez le poste en mode appairage puis ajoutez-le à nouveau.");
                return false;
            }
            WaitConnected(8000);
            string why;
            if (!Subscribe(out why)) Event("info réabonnement : " + why);
            Event("secured " + bleLevel);
            return true;
        }
        catch (Exception e)
        {
            Event("info sécurisation BLE : " + e.Message);
            return bleLevel < 2;
        }
        finally
        {
            bleSecuring = false;
        }
    }

    static void SetLevel(GattProtectionLevel level)
    {
        try
        {
            bleWriteChar.ProtectionLevel = level;
            bleIndChar.ProtectionLevel = level;
        }
        catch (Exception e)
        {
            Event("info niveau de protection : " + e.Message);
        }
    }

    /** Appairage BLE sans code (« Just Works » / confirmation), sinon fenetre d'appairage de Windows. */
    static void Pair(DeviceInformationPairing pairing)
    {
        try
        {
            DeviceInformationCustomPairing custom = pairing.Custom;
            custom.PairingRequested += (s, a) =>
            {
                Event("info appairage BLE : " + a.PairingKind + (string.IsNullOrEmpty(a.Pin) ? "" : " code " + a.Pin));
                a.Accept();
            };
            DevicePairingResult r = Wait(custom.PairAsync(
                DevicePairingKinds.ConfirmOnly | DevicePairingKinds.ConfirmPinMatch | DevicePairingKinds.DisplayPin,
                DevicePairingProtectionLevel.Encryption), 60000);
            Event("info appairage BLE : " + r.Status);
            if (r.Status != DevicePairingResultStatus.Paired && r.Status != DevicePairingResultStatus.AlreadyPaired)
            {
                r = Wait(pairing.PairAsync(DevicePairingProtectionLevel.Encryption), 60000); // fenetre Windows
                Event("info appairage BLE (Windows) : " + r.Status);
            }
        }
        catch (Exception e)
        {
            Event("info appairage BLE impossible : " + e.Message);
        }
    }

    static Socket OpenOne(EndPoint ep)
    {
        var s = new Socket((AddressFamily)AF_BTH, SocketType.Stream, (ProtocolType)BTHPROTO_RFCOMM);
        try
        {
            s.Connect(ep);
            return s;
        }
        catch
        {
            s.Close();
            throw;
        }
    }

    static int Connect(ulong addr)
    {
        stdout = Console.OpenStandardOutput();
        // Commandes : d'abord en Bluetooth classique (SPP, trames GAIA), qui fonctionne sous Windows
        // sans chiffrement BLE (valide sur RT-660, comme HTCommander) ; BLE en repli.
        bool ble = false;
        string sppWhy = "désactivé";
        if (Environment.GetEnvironmentVariable("HTMOD_FORCEBLE") != "1")
        {
            try { Socks[0] = Open(addr, new[] { SppUuid }, new[] { 1 }); }
            catch (Exception e) { sppWhy = e.Message; }
        }
        if (Socks[0] != null)
        {
            Event("connected 0 gaia");
            StartReader(0);
        }
        else
        {
            Event("info Bluetooth classique indisponible (" + sppWhy + "), essai en BLE");
            string bleWhy;
            ble = OpenBle(addr, out bleWhy);
            if (!ble)
            {
                Event("error canal commandes : classique : " + sppWhy + " ; BLE : " + bleWhy);
                return 1;
            }
            Event("connected 0 ble");
        }

        // Le canal audio echoue parfois juste apres le canal de commandes : quelques essais.
        bool noAudio = Environment.GetEnvironmentVariable("HTMOD_NOAUDIO") == "1"; // diagnostic
        for (int attempt = 1; attempt <= 3 && Socks[1] == null && !noAudio; attempt++)
        {
            try
            {
                Socks[1] = Open(addr, new[] { BsAocUuid, GenericAudioUuid }, new[] { 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 }); // 1 = SPP (commandes)
            }
            catch (Exception e)
            {
                Event("error canal audio (essai " + attempt + "/3) : " + e.Message);
                Thread.Sleep(700);
            }
        }
        if (Socks[1] != null)
        {
            Event("connected 1");
            StartReader(1);
        }

        // Boucle d'ecriture : trames recues sur stdin -> socket correspondant.
        Stream stdin = Console.OpenStandardInput();
        byte[] hdr = new byte[5];
        while (true)
        {
            if (!ReadFully(stdin, hdr, 5)) break;
            int ch = hdr[0];
            int len = (hdr[1] << 24) | (hdr[2] << 16) | (hdr[3] << 8) | hdr[4];
            if (len < 0 || len > 1 << 20) break;
            byte[] data = new byte[len];
            if (!ReadFully(stdin, data, len)) break;
            try
            {
                if (ch == 0 && ble) { BleSend(data); continue; }
                Socket s = ch < 2 ? Socks[ch] : null;
                if (s != null) s.Send(data);
            }
            catch (Exception e) { Event("error envoi canal " + ch + " : " + e.Message); }
        }
        // stdin ferme : l'appli Java s'est arretee.
        foreach (Socket s in Socks) if (s != null) try { s.Close(); } catch { }
        if (bleDevice != null) try { bleDevice.Dispose(); } catch { }
        return 0;
    }

    static void StartReader(int ch)
    {
        var t = new Thread(() =>
        {
            byte[] buf = new byte[4096];
            try
            {
                while (true)
                {
                    int n = Socks[ch].Receive(buf);
                    if (n <= 0) break;
                    Write(ch, buf, n);
                }
            }
            catch { }
            Event("closed " + ch);
            if (ch == 0) Environment.Exit(0); // sans canal de commandes, plus rien a faire
        });
        t.IsBackground = true;
        t.Start();
    }

    static bool ReadFully(Stream s, byte[] b, int n)
    {
        int off = 0;
        while (off < n)
        {
            int r = s.Read(b, off, n - off);
            if (r <= 0) return false;
            off += r;
        }
        return true;
    }

    static void Event(string msg)
    {
        byte[] b = Encoding.UTF8.GetBytes(msg);
        Write(2, b, b.Length);
    }

    static void Write(int ch, byte[] data, int len)
    {
        lock (OutLock)
        {
            try
            {
                stdout.WriteByte((byte)ch);
                stdout.WriteByte((byte)(len >> 24));
                stdout.WriteByte((byte)(len >> 16));
                stdout.WriteByte((byte)(len >> 8));
                stdout.WriteByte((byte)len);
                stdout.Write(data, 0, len);
                stdout.Flush();
            }
            catch
            {
                Environment.Exit(0); // tube ferme
            }
        }
    }

    static ulong ParseMac(string s)
    {
        string hex = s.Replace(":", "").Replace("-", "");
        return Convert.ToUInt64(hex, 16);
    }

    static string FormatMac(ulong a)
    {
        var sb = new StringBuilder();
        for (int i = 5; i >= 0; i--)
        {
            sb.Append(((a >> (8 * i)) & 0xFF).ToString("X2"));
            if (i > 0) sb.Append(':');
        }
        return sb.ToString();
    }
}
