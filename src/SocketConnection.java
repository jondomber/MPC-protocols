import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SocketConnection extends Thread implements Connection {
    private Socket connection;

    private int port;
    private String hostname;
    private BufferedOutputStream out;
    private DataInputStream in;

    Lock lock = new ReentrantLock();

    SocketConnection(int port, String hostname) {
            this.port = port;
            this.hostname = hostname;
        // String key = "MZygpewJsCpRrfOr";
    }

    public void run() {
        // We try to connect to a server
        try {
            // System.out.println("Trying: h " + hostname + "p " + port);
            connection = new Socket(hostname, port);
            // System.out.println("Connected to " + hostname + " at port " + port);
        } catch (IOException e) {
        }

        // if no connection was found we are the server
        if(connection == null) {
            // System.out.println("making server socket at " + port);
            try {
                ServerSocket serverSocket = new ServerSocket(port);
                connection = serverSocket.accept();
            } catch (IOException e) {
                // This is probably an exception due to two parties on the same hostname both making
                // a server on the same port because they are setting up at the same time.
                e.printStackTrace();
                System.out.println("IoE");
            }
        }

        // Setup the input and output streams
        try {
            while(true) {
                if (!connection.isConnected()) {
                    sleep(10);
                    continue;
                }
                // out = new DataOutputStream(connection.getOutputStream());
                out = new BufferedOutputStream(connection.getOutputStream());
                InputStream inFromServer = connection.getInputStream();
                in = new DataInputStream(new BufferedInputStream(inFromServer));

                break;
            }
        } catch (IOException e) {
            System.out.println("Exception in in-out stream construction");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /*
    Sending string s goes as follows:
        1. Encrypt s under the shared AES key.
        2. Write how many bytes the int to send is.
        3. concat 2. the the byte representation of s and send it away.
     */
    public void send(byte[] s) {
        long t1 = System.currentTimeMillis();
        lock.lock();
        // System.out.println("length of s " + s.length);
        try {
            /*String enc_out = AES.encrypt(s);
            byte[] res_out = new byte[0];
            if (enc_out != null) {
                res_out = enc_out.getBytes(StandardCharsets.UTF_8);
            }*/
            byte[] numberOfBytesToSend = ByteBuffer.allocate(4).putInt(s.length).array();
            // We have how long the number of bytes to send is and then all the bytes.
            byte[] res_out = concatenate(numberOfBytesToSend, s);
            String r = new String(res_out, StandardCharsets.UTF_8);
            out.write(res_out);
            out.flush();
        } catch (IOException e) {
            System.out.println("Not able to write to out!");
            e.printStackTrace();
        }
        lock.unlock();
        long t2 = System.currentTimeMillis();
        // System.out.println("time send " + (t2-t1));
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
    private boolean running = true;
    public synchronized String receive() {
        byte[] length_bytes = new byte[4];
        while(running) {
            try {
                if ((in.available()>0)) break;
                sleep(10);
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if(!running)
            return null;
        long t1 = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            try {
                // Either this is an error or we are done
                length_bytes[i] = in.readByte();
            } catch (IOException e) {
                return null;
            }
        }
        long t2 = System.currentTimeMillis();
        int length = new BigInteger(length_bytes).intValue();
        byte[] in_bytes = new byte[length];
        t1 = System.currentTimeMillis();
        try {
            in.readFully(in_bytes);
        } catch (IOException e) {
            // Either this is an error or the connection is terminated
            return null;
        }
        t2 = System.currentTimeMillis();
        String received = new String(in_bytes, StandardCharsets.UTF_8);
        // System.out.println("rec " + received);
        t1 = System.currentTimeMillis();
        return received;
/*
        String decrypt = AES.decrypt(received);
        if (decrypt.equals("")) {
            return null;
        }
        return decrypt;
*/
    }

    public void close() {
        try {
            running = false;
            out.flush();
            in.close();
            out.close();
            connection.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
