import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DamgaardNielsen_DK implements MPCProtocol {
    // TODO virker den overhoved rigtigt?? Mange af de beskreder som bliver sendt er de samme. Måske fordi der ofte bliver ganget med 1,
    private int partyNr;
    private BigInteger F; // prime generating field F
    private BigInteger[] x; // inputs of party
    private int n; // number of parties in the protocol
    private int t; // Maximum number of corrupted parties

    public ConnectionHolder connections;
    private SecretSharing ss;
    private int numberOfMults = 0;
    private int numberOfInputs = 0;
    private int numberOfMyinputs = 0;
    private int numberOfMyInputsEvaluated = 0;
    // This is to keep track of ids used to send elements between the parties, to make sure we don't get concurrency issues.
    private int globalPid = 0;
    private Lock lock = new ReentrantLock();
    private SecureRandom sc;
    // Randomness for input
    private BigInteger[] r;

    // Count of how many mult gates we have gone through
    private int mult_count = 0;


    DamgaardNielsen_DK(int partyNr, BigInteger F, BigInteger[] x, int n, String[] hostnames, int[] ports) {
        this.partyNr = partyNr;
        this.F = F;
        this.x = x;
        this.n = n;
        t = (int) Math.ceil( n/2.0-1);
        connections = new ConnectionHolder(n, partyNr, ports, hostnames);
        ss = new SecretSharing(n, F);

        sc = new SecureRandom();
        // TODO we have moved the action of seeding up here -- maybe look more at how to seed!
        sc.nextBoolean();
    }
    private void calcNumberOfMults(Gate[] circuit) {
        int res = 0;
        for (Gate g : circuit) {
            if (g.getType().equals(Type.MULT)) res++;
        }
        numberOfMults = res;
    }
    private void calcNumberOfInputs(Gate[] circuit) {
        int i = 0;
        int j = 0;
        while (true) {
            if(circuit[i].getLabel() == partyNr) j++;
            if(circuit[i].getType().equals(Type.INPUT)) i++;
            else break;
        }
        numberOfInputs = i;
        numberOfMyinputs = j;
    }


    // For running multiple doubleRandoms in parallel.
    private void doubleRandom1(int pid) {
        int b = F.bitLength();
        // Generate doubleRandom si
        BigInteger si;
        // Check if si was generated inside the field.
        do {
            si = new BigInteger(b, sc);
        }
        while (si.compareTo(F) > 0);
        BigInteger[] tSharing = ss.secretSharing(t,si);
        BigInteger[] two_tSharing = ss.secretSharing(2*t,si);


        connections.sendShares(tSharing, pid);

        pid++;

        connections.sendShares(two_tSharing, pid);
    }

    private BigInteger[][] doubleRandom2(int l, int pid) {
        BigInteger[] tShares = connections.receive(pid);
        pid++;
        BigInteger[] two_tShares = connections.receive(pid);
        // TODO Find out how to the the doubleRandom matrix.
        // Randomness extraction matrix
        BigInteger[] M = new BigInteger[n];
        for (int i = 0; i<n; i++) {
            M[i] = new BigInteger(Integer.toString(n)).mod(F);
        }

        // Randomness
        BigInteger[][] res = new BigInteger[2][l];
        BigInteger[] r = new BigInteger[l];
        BigInteger[] R = new BigInteger[l];
        for (int i = 0; i<l; i++) {
            BigInteger[] alphas = new BigInteger[n];

            // We create an array consisting of (α1^i, α2^i, ..., αn^i)
            BigInteger i_bi = new BigInteger(Integer.toString(i));
            for (int k = 0; k < n; k++) {
                alphas[k] = M[k].modPow(i_bi, F);
            }

            r[i] = BigInteger.ZERO;
            R[i] = BigInteger.ZERO;
            for (int j = 0; j<n; j++) {
                // α1^i*r1 + ... + αn^i*rn
                r[i] = (r[i].add(tShares[j].multiply(alphas[j]))).mod(F);
                R[i] = (R[i].add(two_tShares[j].multiply(alphas[j]))).mod(F);
            }
        }
        res[0] = r;
        res[1] = R;
        return res;
    }

    private BigInteger[][] doubleRandom(int l, int pid) {
        int id = pid;
        int m = n - t;
        int k = l;
        BigInteger[] random = new BigInteger[l];
        BigInteger[] double_random = new BigInteger[l];

        connections.setNrElementsToSend(2*((int) Math.ceil((double)l / (n-t))));

        do {
            if (m < k) {
                int finalId = id;
                executor.submit(() -> {
                    doubleRandom1(finalId);
                });
                id+=2;
                k = k - m;
            } else {
                int finalId1 = id;
                executor.submit(() -> {
                    doubleRandom1(finalId1);
                });
                k = 0;
            }
        } while (k != 0);

        k=l;
        Future[] futures = new Future[l/m+1];
        int j = 0;
        id = pid;
        do {
            if (m < k) {
                int finalId2 = id;
                futures[j] = executor.submit(() -> doubleRandom2(m, finalId2));
                j++;
                id+=2;
                k = k - m;
            } else {
                int finalId3 = id;
                int finalK = k;
                futures[j] = executor.submit(() -> doubleRandom2(finalK, finalId3));
                k = 0;
            }
        } while (k != 0);

        int i = 0;
        for (Future f : futures) {
            if (f == null)
                break;
            try {
                BigInteger[][] r = (BigInteger[][]) f.get();
                for (int q = 0; q < r[0].length; q++) {
                    random[i] = r[0][q];
                    double_random[i] = r[1][q];
                    i++;
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        return new BigInteger[][]{random, double_random};
    }

    // For running multiple doubleRandoms in parallel.
    private void random1(int pid) {
        int b = F.bitLength();
        // Generate doubleRandom si
        BigInteger si;
        // Check if si was generated inside the field.
        do {
            si = new BigInteger(b, sc);
        }
        while (si.compareTo(F) > 0);
        BigInteger[] tSharing = ss.secretSharing(t,si);

        connections.sendShares(tSharing, pid);
    }

    private BigInteger[] random2(int l, int pid) {
        BigInteger[] tShares = connections.receive(pid);

        // Randomness extraction matrix
        BigInteger[] M = new BigInteger[n];
        for (int i = 0; i<n; i++) {
            M[i] = new BigInteger(Integer.toString(n)).mod(F);
        }

        // Randomness
        BigInteger[] r = new BigInteger[l];
        for (int i = 0; i<l; i++) {
            BigInteger[] alphas = new BigInteger[n];

            // We create an array consisting of (α1^i, α2^i, ..., αn^i)
            BigInteger i_bi = new BigInteger(Integer.toString(i));
            for (int k = 0; k < n; k++) {
                alphas[k] = M[k].modPow(i_bi, F);
            }

            r[i] = BigInteger.ZERO;
            for (int j = 0; j<n; j++) {
                // α1^i*r1 + ... + αn^i*rn
                r[i] = (r[i].add(tShares[j].multiply(alphas[j]))).mod(F);
            }
        }
        return r;
    }

    private BigInteger[] random(int l, int pid) {
        int id = pid;
        int m = n - t;
        int k = l;

        connections.setNrElementsToSend((int) Math.ceil((double)l / (n-t)));

        BigInteger[] random = new BigInteger[l];
        do {
            if (m < k) {
                int finalId = id;
                executor.submit(() -> {
                    random1(finalId);
                });
                id++;
                k = k - m;
            } else {
                int finalId1 = id;
                executor.submit(() -> {
                    random1(finalId1);
                });
                k = 0;
            }
        } while (k != 0);

        k=l;
        Future[] futures = new Future[l/m+1];
        id = pid;
        int j = 0;
        do {
            if (m < k) {
                int finalId2 = id;
                futures[j] = executor.submit(() -> random2(m, finalId2));
                j++;
                id++;
                k = k - m;
            } else {
                int finalId3 = id;
                int finalK = k;
                futures[j] = executor.submit(() -> random2(finalK, finalId3));
                k = 0;
            }
        } while (k != 0);

        int i = 0;
        for (Future f : futures) {
            if (f == null)
                break;
            try {
                BigInteger[] r = (BigInteger[]) f.get();
                for (BigInteger ri : r) {
                    random[i] = ri;
                    i++;
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        return random;
    }

/*  TODO remove
    private BigInteger[] random_old(int l, int pid) {
        int b = F.bitLength();
        // Generate doubleRandom si
        BigInteger si;
        // Check if si was generated inside the field.
        do {
            si = new BigInteger(b, sc);
        }
        while (si.compareTo(F) > 0);
        BigInteger[] tSharing = ss.secretSharing(t,si);

        connections.sendShares(tSharing, pid);
        BigInteger[] tShares = connections.receive(pid);

        // Randomness extraction matrix
        BigInteger[] M = new BigInteger[n];
        for (int i = 0; i<n; i++) {
            M[i] = BigInteger.ONE;
        }

        // Randomness
        BigInteger[] r = new BigInteger[l];
        for (int i = 0; i<l; i++) {
            BigInteger[] alphas = new BigInteger[n];

            // We create an array consisting of (α1^i, α2^i, ..., αn^i)
            BigInteger i_bi = new BigInteger(Integer.toString(i));
            for (int k = 0; k < n; k++) {
                alphas[k] = M[k].modPow(i_bi, F);
            }

            r[i] = BigInteger.ZERO;
            for (int j = 0; j<n; j++) {
                // α1^i*r1 + ... + αn^i*rn
                r[i] = (r[i].add(tShares[j].multiply(alphas[j]))).mod(F);
            }
        }
        return r;
    }
*/

    // We do the open in three steps so it can be done using a fixed number of threads less than the number of elements
    // to be opened for much improved performance.
    private void Open1(BigInteger share, int pid, int i) {
        int king = i % n;
        connections.sendToPi(king, share, pid);
    }
    private void Open2(int degree, int pid, int i) {
        int king = i % n;
        if (partyNr == king) {
            BigInteger[] x_shares = connections.receive(pid);
            BigInteger x_king = ss.interpolation(degree, x_shares);
            connections.sendElementToAll(x_king, pid+1);
        }
    }
    private BigInteger Open3(int pid, int i) {
        int king = i % n;
        return connections.receiveFromPi(king, pid+1);
    }


    // Bør triples være en lokal klasse?
    // Generate l multiplication triples.

    private ExecutorService executor = Executors.newFixedThreadPool(16);
    private Triples[] triples(int l, int pid) {
        long t1 = System.currentTimeMillis();
        int randomElementsSend = (int) Math.ceil((double)l / (n-t));
        BigInteger[] a = random(l, pid);
        pid += randomElementsSend;
        BigInteger[] b = random(l, pid);
        pid += randomElementsSend;
        BigInteger[][] rR = doubleRandom(l, pid);
        pid += 2*randomElementsSend;
        BigInteger[] r = rR[0];
        BigInteger[] R = rR[1];
        long t2 = System.currentTimeMillis();

        t1 = System.currentTimeMillis();
        // connections.resetNrElementsToSend();
        BigInteger[] d_shares = new BigInteger[l];
        for (int i = 0; i<l; i++) {
                d_shares[i] = (a[i].multiply(b[i])).add(R[i]).mod(F);
        }
        connections.setNrElementsToSend_OPEN(l);
        // connections.resetNrElementsToSend();
        t1 = System.currentTimeMillis();
        BigInteger[] Ds = openParallel(2*t, d_shares, pid);
        t2 = System.currentTimeMillis();

        BigInteger[] c = new BigInteger[l];
        for (int i = 0; i<l; i++) {
            c[i] = Ds[i].subtract(r[i]).mod(F);
        }


        connections.resetNrElementsToSend();
        // for (int i = 0; i<l; i++) c[i] = ((computeC) threads[i]).getC_share();
        Triples[] trips = new Triples[l];
        for (int i = 0; i<l; i++) {
            Triples t = new Triples();
            // System.out.println(i + ", a: " + a[i] + ", b: " + b[i] + ", c: " + c[i] + ", ptnr: " + partyNr);
            t.setABC(a[i], b[i], c[i]);
            trips[i] = t;
        }
        t2 = System.currentTimeMillis();
        long timeD = t2-t1;
        // System.out.println("total time trips " + (timeD + timerand));
        return trips;
    }
    private BigInteger[] input_randomness;
    private  Triples[] multTriples;


    // TODO maybe this should maybe be done in parallel??????
    private void preprocess(Gate[] circuit) {
        // Generate randomness for inputs
        input_randomness = random(numberOfInputs, globalPid);
        globalPid += (numberOfInputs/(n-t)+1);

        connections.setNrElementsToSend(numberOfInputs);
        Future[] futures = new Future[numberOfInputs];
        int pid = globalPid;
        for (int i=0; i<numberOfInputs; i++) {
            int label = circuit[i].getLabel();
            int finalI = i;
            int finalPid1 = pid;
            futures[i] = executor.submit(() -> {
                connections.sendToPi(label, input_randomness[finalI], finalPid1);
            });
            pid++;
        }

        // check if all tasks are done!
        for (Future f : futures) {
            try {
                f.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        pid = globalPid;
        for (int i=0; i<numberOfInputs; i++) {
            int label = circuit[i].getLabel();
            int finalI = i;
            int finalPid = pid;
            futures[i] = executor.submit(() -> {
                if(partyNr == label) {
                    BigInteger[] r_shares = connections.receive(finalPid);
                    r[finalI] = ss.interpolation(t, r_shares);
                }
            });
            pid++;
        }

        for (Future f : futures) {
            try {
                f.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }


        connections.resetNrElementsToSend();
        globalPid = pid;

        // Generate triples for Mults
        multTriples = triples(numberOfMults, globalPid);
        globalPid += 2*numberOfMults + 4*(int) Math.ceil((double)numberOfMults / (n-t));
        System.out.println(partyNr + " preprocess bytes send " + connections.bytesSend);
        System.out.println(partyNr + " preprocess messages send " + connections.messagesSend);

/*
        System.out.println(partyNr + " nSend " + connections.nSend);
        System.out.println(partyNr + " nReceived " + connections.nReceived);
*/
    }
    // Sames as CEPS_old
    public BigInteger add(Gate g) {
        Gate[] inputs = g.getIn();

        return ((inputs[0].getValue()).add(inputs[1].getValue())).mod(F);
    }
    // Same as CEPS_old
    private BigInteger multByConst(Gate g) {
        BigInteger input = (g.getIn()[0]).getValue();
        BigInteger constLabel = new BigInteger(Integer.toString(g.getLabel()));

        return (input.multiply(constLabel)).mod(F);
    }

    private void evalInput1(Gate g, int gateNr, int pid) {
        int label = g.getLabel();
        if (label == partyNr) {
            // delta_i = x + r
            BigInteger delta = (x[numberOfMyInputsEvaluated].add(r[gateNr])).mod(F);

            numberOfMyInputsEvaluated++;
            connections.sendElementToAll(delta, pid);
        }
    }
    private BigInteger evalInput2(Gate g, int gateNr, int pid) {
        int label = g.getLabel();
        BigInteger delta = connections.receiveFromPi(label, pid);
        // share = delta_i - [r_i]
        return (delta.subtract(input_randomness[gateNr])).mod(F);
    }



    // When several sharings are opened in a round, they are opened
    //in parallel, using one execution of Open
    private void eval(Gate[] circuit) {
        /*
           Iterate over all gates in the circuit.
           We assume that the gates of the circuit are in computational order.
           We evaluate the gates in parallel when they are ready, i.e. their input gates has been evaluated.
        */
        // Evaluating inputs
        long t1 = System.currentTimeMillis();
        Future[] futures = new Future[numberOfInputs];
        if (numberOfMyinputs > 0)
            connections.setNrElementsToSend(numberOfMyinputs);
        int pid = globalPid;
        for (int i = 0; i<numberOfInputs; i++) {
            int finalI = i;
            int finalPid = pid;
            futures[i] = executor.submit(() -> {
                Gate g = circuit[finalI];
                evalInput1(g, finalI, finalPid);

            });
            pid = pid + 4;
        }
        /*for (int i = 0; i<numberOfInputs; i++) { // TODO this still works
            try {
                futures[i].get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }*/

        pid = globalPid;
        for (int i = 0; i<numberOfInputs; i++) {
            int finalI = i;
            int finalPid = pid;
            futures[i] = executor.submit(() -> {
                Gate g = circuit[finalI];
                BigInteger res = evalInput2(g, finalI, finalPid);
                g.setValue(res);
                g.setComputed();
            });
            pid = pid + 4;
        }
        for (int i = 0; i<numberOfInputs; i++) {
            try {
                futures[i].get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        long t2 = System.currentTimeMillis();
        System.out.println("duration input " + (t2-t1));

        globalPid = pid;
        int evalSharesBefore = connections.nrOfSharesSend;
        // Evaluating the other gates
        int currentLvl = 1;
        for (int i = numberOfInputs; i<circuit.length;) {
            int countMults = 0;
            int countOuts = 0;
            int j = i;
            // waiting for all gates in the current lvl to get ready
            while(circuit[i].getLevel() == currentLvl) {
                while (gateNotReady(circuit[i])) {
                    try {
                        synchronized (this) {
                            wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (circuit[i].getType() == Type.MULT)
                    countMults++;
                if (circuit[i].getType() == Type.OUTPUT)
                    countOuts++;
                i++;
                if (i == circuit.length)
                    break;
            }

            // Start to compute all the gates which are ready.
            Gate[] mults = new Gate[countMults];
            Gate[] outs = new Gate[countOuts];
            int multCounter = 0;
            int outCounter = 0;
            for (int k=j; k<i; k++) {
                Gate g = circuit[k];
                Type type = g.getType();
                switch(type) {
                    case ADD:
                        g.setValue(add(g));
                        g.setComputed();
                        break;

                    case CONST:
                        g.setValue(multByConst(g));
                        g.setComputed();
                        break;

                    case MULT:
                        mults[multCounter] = g;
                        multCounter++;
                        break;

                    case OUTPUT:
                        outs[outCounter] = g;
                        outCounter++;

                        break;

                    case INPUT:
                        System.out.println("EROOR");
                        break;
                }
            }
            currentLvl++;

            // If there is no mult gates we are done, else we have to compute their value.
            if(countMults == 0 && outCounter == 0)
                continue;

            if (countMults != 0 || countOuts != 0)
                connections.setNrElementsToSend_OPEN(2*countMults + countOuts);

            // evaluating outs
            if (outCounter > 0) {
                System.out.println(partyNr + " before out messages " + connections.messagesSend);
                BigInteger[] outGateValues = new BigInteger[outCounter];
                for (int k = 0; k < outCounter; k++) {
                    outGateValues[k] = (outs[k].getIn()[0]).getValue();
                }

                BigInteger[] res = openParallel(t, outGateValues, globalPid);
                System.out.println(partyNr + " after out messages send " + connections.messagesSend);
                break;
            }

            BigInteger[] alphasAndBetas = new BigInteger[2*countMults];
            BigInteger[] as = new BigInteger[countMults];
            BigInteger[] bs = new BigInteger[countMults];
            BigInteger[] cs = new BigInteger[countMults];

            for (int i1 = 0, multsLength = mults.length; i1 < multsLength; i1++) {
                Gate g = mults[i1];
                Gate[] ins = g.getIn();
                BigInteger x1 = ins[0].getValue();
                BigInteger x2 = ins[1].getValue();
                Triples trip = multTriples[mult_count];
                mult_count++;
                BigInteger a = trip.getA();
                as[i1] = a;
                BigInteger b = trip.getB();
                bs[i1] = b;
                cs[i1] = trip.getC();
                alphasAndBetas[i1] = x1.add(a);
                alphasAndBetas[i1 + countMults] = x2.add(b);
            }
            BigInteger[] opens = openParallel(t, alphasAndBetas, globalPid);
            globalPid = globalPid + countMults * 4;

            for (int k = 0; k < countMults; k++) {
                Gate g = mults[k];
                BigInteger alpha = opens[k];
                BigInteger beta = opens[k + countMults];
                g.setValue(((((alpha.multiply(beta)).subtract( alpha.multiply(bs[k]) )).subtract(beta.multiply(as[k]))).add(cs[k])).mod(F));
                g.setComputed();
            }
        }

        // Resetting fields if the protocol is to be runProtocol again!
        connections.resetNrElementsToSend();
        executor.shutdown();
        executor.isTerminated();
        executor = Executors.newFixedThreadPool(8);
        numberOfMyInputsEvaluated = 0;
        mult_count = 0;
        input_randomness = null;
        r = new BigInteger[numberOfInputs];
        multTriples = null;
    }

    private boolean gateNotReady(Gate g) {
        Type t = g.getType();
        Gate[] ins = g.getIn();
        if(t.equals(Type.INPUT)) {
            return false;
        }
        // ADD and mult are the only gates with two inputs.
        else if (t.equals(Type.ADD) ||  t.equals(Type.MULT)) {
            // Return true if both the inptus are set -- otherwise return false
            return (!ins[0].getComputed() || !ins[1].getComputed());
        }
        // Lastly we consider Output, Const
        else if (t.equals(Type.CONST) || t.equals(Type.OUTPUT)) {
            return !ins[0].getComputed();
        }
        else {
            System.out.println("Something is wrong is gate ready!!!!");
            return true;
        }
    }

    @Override
    public long[] runProtocol(Gate[] circuit) {
        System.out.println("Running Damgård-Nielsen (DK)");

        calcNumberOfInputs(circuit);
        calcNumberOfMults(circuit);
        r = new BigInteger[numberOfInputs]; // The randomness for my input.

        long startTime;
        long endTime;
        long[] runtimes = new long[3];

        // Preprocess
        System.out.println("Preprocess");
        startTime = System.nanoTime();
        /////////////////////////////////////////////////////////////////
        preprocess(circuit);
        /////////////////////////////////////////////////////////////////
        endTime = System.nanoTime();

        long duration_preprocess = (endTime - startTime)/1000000;
        // runtimes[0] = duration_preprocess;
        // runtimes[1] = connections.bytesSend;

        System.out.println("Duration Preprocess: " + duration_preprocess + " ms");
        System.out.println("shares send preprocess " + connections.nrOfSharesSend);

        System.out.println("Eval");
        startTime = System.nanoTime();
        /////////////////////////////////////////////////////////////////
        eval(circuit);
        /////////////////////////////////////////////////////////////////
        endTime = System.nanoTime();

        long duration_eval = (endTime - startTime)/1000000;
        // runtimes[2] = duration_eval;
        // runtimes[3] = connections.bytesSend;
        // runtimes[4] = connections.time;

        connections.time = 0;


        System.out.println("Duration Eval: " + duration_eval + " ms");

        long duration_protocol = duration_preprocess + duration_eval;
        runtimes[0] = connections.nrOfSharesSend;
        runtimes[1] = connections.messagesSend;
        runtimes[2] = duration_protocol;
        System.out.println("\nDuration protocol: " + duration_protocol + " ms");

        System.out.println(partyNr + " bytes send " + connections.bytesSend);
        System.out.println(partyNr + " shares send " + connections.nrOfSharesSend);
        System.out.println(partyNr + " messages send " + connections.messagesSend);
        connections.nrOfSharesSend = 0;
        connections.bytesSend = 0;
        connections.messagesSend = 0;
        System.out.println("global pid " + globalPid);
        globalPid = 0;

        System.out.println("END! \n######################################################\n\n");

        return runtimes;
    }

    @Override
    public void setup() {
        System.out.println("setting up connections");

        // Setup
        long startTime = System.nanoTime();
        /////////////////////////////////////////////////////////////////
        connections.setup();
        /////////////////////////////////////////////////////////////////
        long endTime = System.nanoTime();

        long duration_setup = (endTime - startTime)/1000000;
        System.out.println("Duration setup: " + duration_setup + " ms");
    }

    @Override
    // Stopping the attempts to receive!
    public void stop() {
        connections.stop();
        executor.shutdown();
        executor.isTerminated();
    }

    // This method allows us to do a number of opens in parallel.
    private BigInteger[] openParallel(int degree, BigInteger[] elements, int pid) {
        int thisPid = pid;
        for (int i = 0; i < elements.length; i++) {
            BigInteger element = elements[i];
            int finalPid = thisPid;
            int finalI = i;
            executor.submit(() -> Open1(element, finalPid, finalI));
            thisPid += 2;
        }
        thisPid = pid;
        for (int i = 0; i < elements.length; i++) {
            int finalPid = thisPid;
            int finalI = i;
            executor.submit(() -> Open2(degree, finalPid, finalI));
            thisPid += 2;
        }
        thisPid = pid;
        Future[] futures = new Future[elements.length];
        for (int i = 0; i<elements.length; i++) {
            int finalPid = thisPid;
            int finalI = i;
            futures[i] = executor.submit(() -> Open3(finalPid, finalI));
            thisPid+=2;
        }
        BigInteger[] res = new BigInteger[elements.length];
        for (int i = 0; i<elements.length; i++) {
            try {
                res[i] = (BigInteger) futures[i].get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        return res;
    }
}