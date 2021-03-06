import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.Thread.sleep;


public class ConnectionHolder {
    private int n;
    private int partyNr;

    private int[] ports;
    private String[] hostnames;
    private Thread[] connections;
    private boolean receiveContinue = true;
    private int number_of_elements_to_send = 1;
    private int elements_send_currently = 0;
    private String[] message;

    private ConcurrentHashMap<Integer, BigInteger[]> receivedFromPid = new ConcurrentHashMap<>();

    int bytesSend = 0;
    int nrOfSharesSend = 0;
    int messagesSend = 0;
    public long time;
    private Receiver[] receivers;


    public ConnectionHolder(int n, int partyNr, int[] ports, String[] hostnames) {
        this.n = n;
        this.partyNr = partyNr;

        this.ports = ports;
        this.hostnames = hostnames;
        connections = new Thread[n];
        time = 0;
        message = new String[n];
    }

    // Setup connection to all the parties.
    public void setup() {
        for (int i = 0; i<n; i++) {
            // IF we are trying to make a connection with ourself we make a local connection.
            if (i == partyNr) connections[i] = new LocalConnection();
                // else we make a Socket connection.
            else {
                connections[i] = new SocketConnection(ports[i], hostnames[i]);
                connections[i].start();
            }
        }
        // Make sure all the connections are set up before continuing.
        for (int i = 0; i<n; i++) {
            while (connections[i].isAlive()) {
                try {
                    sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        // This is where we store the element without pids.
        receivedFromPid.put(-1, new BigInteger[n]);

        receivers = new Receiver[n];
        for (int i = 0; i<n; i++) {
            // System.out.println(partyNr + " Receiver " + i + " started");
            receivers[i] = new Receiver(i);
            receivers[i].start();
        }
    }

    // Stop the connections
    public void stop() {
        receiveContinue = false;
        for (Thread t : connections)
            ((Connection) t).close();
        for (Thread t : connections) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        for (Receiver r : receivers) {
            try {
                r.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // A class extending thread for running in parallel to look for received messages from its designated party.
    class Receiver extends Thread {
        private int connectionNr;

        Receiver(int connectionNr) {
            this.connectionNr = connectionNr;
        }

        public void run() {
            while (receiveContinue) {
                String received = ((Connection) connections[connectionNr]).receive();

                // If this is the case we are either done or there is an error!
                if(received == null)
                    break;

                if (received.startsWith("%")) {
                    message[connectionNr] = received.substring(1);
                } else {
                    pid_and_BigInteger[] pidres_array = getPidAndRes(received);
                    for (pid_and_BigInteger pidres : pidres_array) {
                        int pid = pidres.pid;
                        receivedFromPid.putIfAbsent(pid, new BigInteger[n]);

                        BigInteger res = pidres.x;
                        receivedFromPid.get(pid)[connectionNr] = res;

                    }
                }

                synchronized (ConnectionHolder.this) {
                    ConnectionHolder.this.notifyAll();
                }
            }
            ((LocalConnection) connections[partyNr]).close();
        }

    }

    private Lock sendLock = new ReentrantLock();
    private pid_and_BigInteger[][] sendShares_Array;
    public long timeSending = 0;

    public void sendShares(BigInteger[] shares, int pid) {
        sendLock.lock();
        nrOfSharesSend += shares.length;

        if(sendShares_Array == null)
            sendShares_Array = new pid_and_BigInteger[number_of_elements_to_send][];
        pid_and_BigInteger[] pidres_shares = new pid_and_BigInteger[n];

        // Add the shares to the array of shares to be send
        for (int i = 0; i<n; i++) {
            pidres_shares[i] = new pid_and_BigInteger(pid, shares[i]);
        }
        sendShares_Array[elements_send_currently] = pidres_shares;
        elements_send_currently++;

        // all the shares that are to be send has been received.
        if(elements_send_currently == number_of_elements_to_send) {
            long t1 = System.currentTimeMillis();
            byte[][] res = new byte[n][];
            for (int i = 0; i < n; i++) {
                res[i] =  new byte[40*number_of_elements_to_send];
            }
            int[] count = new int[n];
            for (int k = 0; k<sendShares_Array.length; k++) {
                pid_and_BigInteger[] shares_array = sendShares_Array[k];
                for (int j = 0; j < n; j++) {
                    pid_and_BigInteger pid_x = shares_array[j];
                    String str = pid_x.pid + "#" + pid_x.x;
                    if (k != sendShares_Array.length - 1) {
                        str += "&";
                    }
                    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                    for (byte aByte : bytes) {
                        res[j][count[j]] = aByte;
                        count[j]++;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                res[i] = Arrays.copyOfRange(res[i], 0, count[i]);
            }
            for (int i = 0; i<n; i++) {
                ((Connection) connections[i]).send(res[i]);
                messagesSend++;
                bytesSend +=res[i].length;
            }
            elements_send_currently = 0;
            sendShares_Array = null;

            long t2 = System.currentTimeMillis();
            timeSending+=t2-t1;
        }
        sendLock.unlock();
    }

    private ArrayList<ArrayList<pid_and_BigInteger>> sendToPi_array;
    private Lock sendPiLock = new ReentrantLock();
    private int elements_send_currently_Pi = 0;

    void sendToPi(int i, BigInteger x, int pid) {
        sendLock.lock();
        nrOfSharesSend++;
        if(sendToPi_array == null) {
            sendToPi_array = new ArrayList<>();
            for(int k = 0; k<n; k++) {
                sendToPi_array.add(null);
            }
        }

        if (sendToPi_array.get(i) == null) {
            sendToPi_array.set(i, new ArrayList<>(number_of_elements_to_send));
        }
        sendToPi_array.get(i).add(new pid_and_BigInteger(pid, x));
        elements_send_currently_Pi++;

        if(elements_send_currently_Pi == number_of_elements_to_send) {
            byte[][] res = new byte[n][];
            for (int k = 0; k < n; k++) {
                res[k] =  new byte[40*number_of_elements_to_send];
            }
            int[] count = new int[n];
            for (int k=0; k<n; k++) {
                ArrayList<pid_and_BigInteger> shares_array = sendToPi_array.get(k);
                if (shares_array == null)
                    continue;
                int size = shares_array.size();
                for (int j = 0; j<size; j++) {
                    pid_and_BigInteger pid_x = shares_array.get(j);
                    String str = pid_x.pid + "#" + pid_x.x;
                    if (j != size - 1) {
                        str += "&";
                    }
                    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                    for (byte aByte : bytes) {
                        res[k][count[k]] = aByte;
                        count[k]++;
                    }
                }
            }
            for (int k = 0; k < n; k++) {
                res[k] = Arrays.copyOfRange(res[k], 0, count[k]);
            }
            for (int m=0; m<n; m++) {
                if ( sendToPi_array.get(m) != null) {
                    String s = new String(res[m], StandardCharsets.UTF_8);
                    ((Connection) connections[m]).send(res[m]);
                    bytesSend += res[m].length;
                    messagesSend++;
                }
            }
            elements_send_currently_Pi = 0;
            sendToPi_array = null;
        }
        sendLock.unlock();
    }

    private pid_and_BigInteger[] elements_to_send_all = null;
    private int number_of_elements_to_send_all = 1;
    private int elements_send_currently_all = 0;
    private Lock allLock = new ReentrantLock();

    public void sendElementToAll(BigInteger x, int pid) {
        sendLock.lock();
        nrOfSharesSend+=n;
        if(elements_to_send_all == null)
            elements_to_send_all = new pid_and_BigInteger[number_of_elements_to_send_all];

        elements_to_send_all[elements_send_currently_all] = new pid_and_BigInteger(pid, x);


        elements_send_currently_all++;

        if(elements_send_currently_all == number_of_elements_to_send_all) {
            byte[] res = new byte[40*number_of_elements_to_send_all];
            int count = 0;
            for (int j = 0; j< elements_to_send_all.length; j++) {
                pid_and_BigInteger pid_x = elements_to_send_all[j];
                String str = pid_x.pid + "#" + pid_x.x;
                if (j != elements_to_send_all.length - 1) {
                    str += "&";
                }
                byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                for (byte aByte : bytes) {
                    res[count] = aByte;
                    count++;
                }
            }
            res = Arrays.copyOfRange(res, 0, count);

            for (int i = 0; i<n; i++) {
                String s = new String(res, StandardCharsets.UTF_8);
                ((Connection) connections[i]).send(res);
                bytesSend += res.length;
                messagesSend++;
            }
            elements_send_currently_all = 0;
            elements_to_send_all = null;
            long t2 = System.currentTimeMillis();
        }
        sendLock.unlock();
    }

    public void sendMessageToAll(String s) {
        for (int i = 0; i<n; i++) {
            ((Connection) connections[i]).send(s.getBytes());
        }
    }


    public BigInteger[] receive(int pid) {

        // We wait until the value of pid is put in the map -- which is done when an element is
        // received and thus we wait to check until notified from the receiver.
        while(receivedFromPid.get(pid) == null) {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        BigInteger[] recPid = receivedFromPid.get(pid);
        for (int i = 0; i<n; i++) {
            while (recPid[i] == null) {
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return receivedFromPid.remove(pid);
    }


    public BigInteger receiveFromPi(int i, int pid) {
        long t1 = System.nanoTime();
        // wait until receviedFromPid has a field with pid.
        while(receivedFromPid.get(pid) == null) {
            synchronized (this) {
                try {
                    wait(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        while(receivedFromPid.get(pid)[i] == null) {
            synchronized (this) {
                try {
                    wait(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        BigInteger res = receivedFromPid.remove(pid)[i];
        long t2 = System.nanoTime();
        time = (t2-t1)/1000000;
        return res;
    }

    public String[] receiveMessage() {
        while(message == null) {
            synchronized (this) {
                try {
                    wait(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        String[] res = new String[n];
        for (int i = 0; i < n; i++) {
            while(message[i] == null) {
                synchronized (this) {
                    try {
                        wait(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            res[i] = message[i];
        }

        return res;
    }

    // a class for when receiving an element to contain the pid and the value received.
    class pid_and_BigInteger {
        final int pid;
        final BigInteger x;

        pid_and_BigInteger(int pid, BigInteger x) {
            this.pid = pid;
            this.x = x;
        }
    }
    private pid_and_BigInteger[] getPidAndRes(String received) {
        pid_and_BigInteger[] pidres = new pid_and_BigInteger[received.length()];

        int i = 0;
        while(received.contains("&")) {
            int hashTagidx = received.indexOf("#");
            String pid = received.substring(0, hashTagidx);
            String res = received.substring(hashTagidx + 1, received.indexOf("&"));

            pidres[i] = new pid_and_BigInteger(Integer.parseInt(pid), new BigInteger(res));

            i++;
            received = received.substring(received.indexOf("&") + 1);
        }
        int hashTagidx = received.indexOf("#");
        if(hashTagidx == -1) {
            if (received.equals("BOTTOM"))
                return new pid_and_BigInteger[0];
        }
        String pid =  null;

        pid = received.substring(0, hashTagidx);

        String res = received.substring(hashTagidx + 1);

        pidres[i] = new pid_and_BigInteger(Integer.parseInt(pid), new BigInteger(res));

        // removing all the null values of array
        pidres = Arrays.stream(pidres).filter(Objects::nonNull).toArray(pid_and_BigInteger[]::new);
        return pidres;
    }

    void setNrElementsToSend_OPEN(int l) {
        sendLock.lock();

        number_of_elements_to_send = l;
        number_of_elements_to_send_all = computeNumberOfElementsToSend_OPEN(l);

        sendLock.unlock();
    }

    void setNrElementsToSend_MULT(int l) {
        allLock.lock();
        sendLock.lock();
        sendPiLock.lock();

        number_of_elements_to_send = l;
        number_of_elements_to_send_all = computeNumberOfElementsToSend_MULT(l);

        sendLock.unlock();
    }


    void setNrOfElementsToSend(int l) {
        sendLock.lock();

        number_of_elements_to_send = l;
        number_of_elements_to_send_all = l;

        sendLock.unlock();
    }

    private int computeNumberOfElementsToSend_OPEN(int l) {
        int ln_floor = Math.floorDiv(l, n);
        int residue = l - ln_floor*n;
        if (partyNr < residue) {
            return (ln_floor + 1);
        }
        return ln_floor;
    }

    private int computeNumberOfElementsToSend_MULT(int l) {
        // if (l < n*2)
        int k = l/2;
        int ln_floor = Math.floorDiv(k, n);
        int residue = k - ln_floor*n;
        if (partyNr < residue) {
            return (ln_floor + 1)*2;
        }
        return ln_floor*2;
    }

    public void resetNrElementsToSend() {
        sendLock.lock();

        number_of_elements_to_send = 1;
        number_of_elements_to_send_all = 1;

        sendLock.unlock();
    }
}
