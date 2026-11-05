package com.eberhard.rokidmirror.dexd;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The DeX helper that lives on the phone. Started once per boot with shell rights (app_process
 * through Wireless debugging), it then works without ADB: on the hotspot, any Wi-Fi, anywhere.
 *
 *  - control  TCP 127.0.0.1:27185  lines: "start [WxH/dpi]", "stop", "status"  (Rokid Mirror uses it)
 *  - video    TCP 0.0.0.0:27184    bridged to scrcpy-server's abstract socket for the glasses
 *  - find     UDP 7072             "RMDEX?" is answered with "RMDEX 27184"
 *
 * While "started", scrcpy-server runs as a child process with a virtual display that Samsung
 * DeX takes over; it exits whenever the glasses disconnect and is started again.
 */
public final class Main {
    static final int CONTROL_PORT = 27185;
    static final int VIDEO_PORT = 27184;
    static final int FIND_PORT = 7072;
    static final String DIR = "/data/local/tmp/rokid";
    static final String SERVER_JAR = DIR + "/scrcpy-server.jar";
    static final String SERVER_VERSION = "5.0.1";

    static volatile boolean wanted = false;
    static volatile String size = "640x480/120";
    static volatile Process server;
    static volatile long serverSince;
    static PrintStream log;

    public static void main(String[] args) throws Exception {
        log = new PrintStream(new FileOutputStream(new File(DIR, "dexd.log"), true), true);
        log("dexd up, pid " + android.os.Process.myPid());
        new Thread(Main::controlLoop, "control").start();
        new Thread(Main::videoLoop, "video").start();
        new Thread(Main::findLoop, "find").start();
        serverLoop();
    }

    static void log(String s) {
        log.println(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s);
    }

    // ---- scrcpy-server as a child, restarted while wanted

    static void serverLoop() {
        while (true) {
            try {
                Process p = server;
                if (wanted && (p == null || !p.isAlive())) launchServer();
                if (!wanted && p != null && p.isAlive()) { p.destroy(); log("server stopped"); }
                Thread.sleep(1000);
            } catch (Exception e) {
                log("loop: " + e);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }

    static void launchServer() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("app_process");
        cmd.add("/");
        cmd.add("com.genymobile.scrcpy.Server");
        cmd.add(SERVER_VERSION);
        cmd.add("tunnel_forward=true");
        cmd.add("audio=false");
        cmd.add("control=false");
        cmd.add("video_codec=h264");
        cmd.add("max_size=640");
        cmd.add("video_bit_rate=2000000");
        cmd.add("max_fps=30");
        cmd.add("new_display=" + size);
        cmd.add("send_device_meta=false");
        cmd.add("send_dummy_byte=false");
        cmd.add("send_stream_meta=true");
        cmd.add("send_frame_meta=true");
        cmd.add("stay_awake=true");
        cmd.add("cleanup=false");   // cleanup would delete the jar after each session
        cmd.add("log_level=info");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("CLASSPATH", SERVER_JAR);
        Process p = pb.start();
        server = p;
        serverSince = System.currentTimeMillis();
        log("server started (" + size + ")");
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) log("  " + line);
            } catch (IOException ignored) { }
            log("server exited " + p.exitValue());
        }, "server-log");
        t.setDaemon(true);
        t.start();
    }

    // ---- control: the phone app (or the script) talks to us on loopback

    static void controlLoop() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", CONTROL_PORT));
            while (true) {
                try (Socket s = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                    String line = in.readLine();
                    if (line == null) continue;
                    String[] parts = line.trim().split("\\s+");
                    switch (parts[0]) {
                        case "start":
                            if (parts.length > 1) size = parts[1];
                            wanted = true;
                            out.println("ok started " + size);
                            log("control: start " + size);
                            break;
                        case "stop":
                            wanted = false;
                            Process p = server;
                            if (p != null) p.destroy();
                            out.println("ok stopped");
                            log("control: stop");
                            break;
                        default:
                            Process q = server;
                            out.println((wanted ? "started " + size : "stopped") +
                                (q != null && q.isAlive() ? " server-alive " + (System.currentTimeMillis() - serverSince) / 1000 + "s" : " server-off"));
                    }
                } catch (IOException e) {
                    log("control: " + e);
                }
            }
        } catch (IOException e) {
            log("control port: " + e);
        }
    }

    // ---- video: glasses <-> scrcpy's abstract socket

    static void videoLoop() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("0.0.0.0", VIDEO_PORT));
            while (true) {
                Socket client = ss.accept();
                new Thread(() -> bridge(client), "bridge").start();
            }
        } catch (IOException e) {
            log("video port: " + e);
        }
    }

    static void bridge(Socket client) {
        String who = String.valueOf(client.getRemoteSocketAddress());
        LocalSocket local = new LocalSocket();
        try {
            client.setTcpNoDelay(true);
            // The server listens only while it is up and idle; give a fresh start a moment.
            IOException last = null;
            for (int i = 0; i < 20; i++) {
                try {
                    local.connect(new LocalSocketAddress("scrcpy", LocalSocketAddress.Namespace.ABSTRACT));
                    last = null;
                    break;
                } catch (IOException e) {
                    last = e;
                    Thread.sleep(250);
                }
            }
            if (last != null) throw last;
            log("glasses " + who + " bridged");
            Thread up = new Thread(() -> pump(client, local), "up");
            up.setDaemon(true);
            up.start();
            pumpStreams(local.getInputStream(), client.getOutputStream());
        } catch (Exception e) {
            log("bridge " + who + ": " + e);
        } finally {
            try { local.close(); } catch (IOException ignored) { }
            try { client.close(); } catch (IOException ignored) { }
            log("glasses " + who + " gone");
        }
    }

    static void pump(Socket from, LocalSocket to) {
        try {
            pumpStreams(from.getInputStream(), to.getOutputStream());
        } catch (IOException ignored) {
        } finally {
            try { to.close(); } catch (IOException ignored) { }
        }
    }

    static void pumpStreams(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    // ---- find: answer the glasses' broadcast

    static void findLoop() {
        try (DatagramSocket socket = new DatagramSocket(FIND_PORT)) {
            socket.setBroadcast(true);
            byte[] buf = new byte[64];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String text = new String(p.getData(), 0, p.getLength());
                if (text.startsWith("RMDEX?")) {
                    byte[] reply = ("RMDEX " + VIDEO_PORT).getBytes();
                    socket.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                }
            }
        } catch (IOException e) {
            log("find port: " + e);
        }
    }
}
