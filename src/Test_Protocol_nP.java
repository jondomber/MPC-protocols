import java.math.BigInteger;

public class Test_Protocol_nP extends Thread {
    private int[] ports;
    private BigInteger[] x;
    private int partyNr;
    private int n;
    private int parallelCircuits;
    private int protocolNr;

    private Test_Protocol_nP(int[] ports, BigInteger[] x, int partyNr, int n, int protocolNr, int parallelCircuits) {
        this.ports = ports;
        this.x = x;
        this.partyNr = partyNr;
        this.n = n;
        this.protocolNr = protocolNr;
        this.parallelCircuits = parallelCircuits;
    }

    @Override
    public void run() {
        BigInteger F;

        F = new BigInteger("11");
        // F = new BigInteger("2305843009213693951");

        String[] hostnames = new String[n];
        hostnames[0] = "localhost";
        hostnames[1] = "localhost";
        hostnames[2] = "localhost";

        // Constructing circuit
        // TODO: Make a smarter way to make a circuit.

        Gate in1 = new Gate(Type.INPUT, 0);
        Gate in2 = new Gate(Type.INPUT, 1);
        in1.setLevel(0);
        in2.setLevel(0);
        // Gate in3 = new Gate(Type.INPUT, 1);
        Gate add = new Gate(Type.ADD);
        add.setLevel(1);
        add.setIn(new Gate[]{in1, in2});

        Gate mult = new Gate(Type.MULT);
        mult.setLevel(2);
        Gate[] in = {add, in1};
        mult.setIn(in);
        Gate mult2 = new Gate(Type.MULT);
        mult2.setLevel(1);
        mult2.setIn(new Gate[]{in1, in2});

        Gate[] outMult = {mult};
        Gate[] outMult2 = {mult2};
        Gate out1 = new Gate(Type.OUTPUT);
        Gate out2 = new Gate(Type.OUTPUT);
        Gate out3 = new Gate(Type.OUTPUT);
        out1.setLevel(2);
        out2.setLevel(2);
        out3.setLevel(2);
        Gate[] outIn = {in1};
        out1.setIn(outMult2);
        out2.setIn(outMult2);
        out3.setIn(outMult2);
        Gate[] circuit;
        circuit = new Gate[]{in1, in2, mult2, out1, out2, out3};


        ImportCircuit imp = new ImportCircuit();
        circuit = imp.importCircuit("Test_Circuits/adder_32bit.txt");
        circuit = mergeRec(circuit, parallelCircuits);

        // circuit = imp.importCircuit("Test_Circuits/Test.txt");
        // circuit = imp.importCircuit("Test_Circuits/AES-non-expanded.txt");
        // Gate[] circuit1 = imp.importCircuit("Test_Circuits/AES-non-expanded.txt");

        // #########################################################################################################################################
        // protocolNr = 1;
        // #########################################################################################################################################
        MPCProtocol protocol;
        if(protocolNr == 0) {
            protocol = new CEPS(partyNr, F, x, n, hostnames, ports);
        } else if (protocolNr == 1) {
            protocol = new DamgaardNielsen(partyNr, F, x, n, hostnames, ports);
        } else if (protocolNr == 2){
            protocol = new DamgaardNielsen_DK(partyNr, F, x, n, hostnames, ports);
        } else
            protocol = new CGH18(partyNr, F, x, n, hostnames, ports);
        protocol.setup();
        int runs = 1;
        long[] durations = new long[runs];
        for (int i = 0; i < runs; i++) {
            System.out.println("¤¤¤ i: " + i + " ¤¤¤");
            long[] res = protocol.runProtocol(circuit);
            if (res  != null)
                durations[i] = res[res.length-1];
            // circuit = parallelAdd();
        }
        long duration = 0;
        for (long duration1 : durations) {
            duration += duration1;
        }
        duration = duration/durations.length;
        System.out.println(partyNr + " DURATION " + duration);
        try {
            sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        protocol.stop();
    }



    // merge a circuit together as to evaluate it in parallel an even number of times.
    private static Gate[] mergeRec(Gate[] gates, int n) {
        int k = n/2;
        if(k == 1)  {
            return merge(gates, gates);
        } else {
            return merge(mergeRec(gates, k), mergeRec(gates, k));
        }
    }


    private static Gate[] merge(Gate[] a, Gate[] b) {
        int length = a.length + b.length;
        Gate[] answer = new Gate[length];
        int k = 0, i = 0;

        while (k < length-1) {
            answer[k] = a[i];
            k++;
            answer[k] = b[i];
            k++;
            i++;
        }

        return answer;
    }

    public static void main(String[] args) {
        ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        int n = 3;
        int protocolNr = 3;
        int nrParallel = 128;
        int nrIn = 32*nrParallel;
        BigInteger[] in = new BigInteger[nrIn];

        for (int i = 0; i<nrIn; i++) {
            in[i] = BigInteger.ONE;
        }
        ///////////////////////////////////////////////////////////////////////////////////////////////////////////
        int[][] ports = GeneratePortnumbers.getPortnumbers(n);
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Test_Protocol_nP(ports[i], in, i, n, protocolNr, nrParallel);
            threads[i].start();
            try {
                sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
