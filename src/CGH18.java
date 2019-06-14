import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CGH18 implements MPCProtocol {
    private int partyNr;
    private BigInteger F; // prime generating field F
    private BigInteger[] x; // inputs of party
    private int n; // number of parties in the protocol
    private int t; // Maximum number of corrupted parties

    public ConnectionHolder connections;
    private SecretSharing ss;
    private int numberOfMults = 0;
    private int numberOfInputs = 0;
    private int numberOfOutputs = 0;
    private int numberOfMyInputsEvaluated = 0;
    // This is to keep track of ids used to send elements between the parties, to make sure we don't get concurrency issues.
    private int globalPid = 0;
    private Lock lock = new ReentrantLock();
    private SecureRandom sc;
    private ExecutorService executor = Executors.newFixedThreadPool(8);
    private int numberOfMyInputs;


    CGH18(int partyNr, BigInteger F, BigInteger[] x, int n, String[] hostnames, int[] ports) {
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
    private void calcNumberOfMultsAndOuts(Gate[] circuit) {
        for (Gate g : circuit) {
            if (g.getType().equals(Type.MULT)) numberOfMults++;
            if (g.getType().equals(Type.OUTPUT)) numberOfOutputs++;
        }
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
        numberOfMyInputs = j;
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

        connections.setNrElementsToSend(2*((int) Math.ceil((double)l / m)));
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

    private Lock evalLock = new ReentrantLock();
    private void evalInput1(Gate g, BigInteger r, int pid) {
        int label = g.getLabel();
        // TODO
        evalLock.lock();
        if (label == partyNr) {
            // delta_i = x + r
            BigInteger delta = (x[numberOfMyInputsEvaluated].add(r)).mod(F);

            numberOfMyInputsEvaluated++;
            connections.sendElementToAll(delta, pid);
        }
        evalLock.unlock();
    }
    private BigInteger evalInput2(Gate g, BigInteger inputRandomness, int pid) {
        int label = g.getLabel();
        BigInteger delta = connections.receiveFromPi(label, pid);
        // share = delta_i - [r_i]
        return delta;
    }

    // Secure Sharing of inputs
    private void inputSharing(Gate[] circuit) {
        BigInteger[] input_randomness = random_old(numberOfInputs, globalPid);
        globalPid = (int) Math.ceil((double)numberOfMults / (n-t));
        BigInteger[] r = new BigInteger[numberOfInputs];

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

        globalPid = pid;
        if (numberOfMyInputs > 0) {
            connections.setNrElementsToSend(numberOfMyInputs);
        }

        pid = globalPid;

        futures = new Future[numberOfInputs];
        for (int i = 0; i<numberOfInputs; i++) {
            int finalI = i;
            int finalPid = pid;
            futures[i] = executor.submit(() -> {
                Gate g = circuit[finalI];
                evalInput1(g, r[finalI], finalPid);

            });
            pid++;
        }
        for (int i = 0; i<numberOfInputs; i++) { // TODO
            try {
                futures[i].get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        BigInteger[] deltas = new BigInteger[numberOfInputs];
        pid = globalPid;
        for (int i = 0; i<numberOfInputs; i++) {
            int finalI = i;
            int finalPid = pid;
            futures[i] = executor.submit(() -> {
                Gate g = circuit[finalI];
                deltas[finalI] = evalInput2(g, input_randomness[finalI], finalPid);
            });
            pid++;
        }
        for (int i = 0; i<numberOfInputs; i++) {
            try {
                futures[i].get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        // checking that the deltas send were the same for everyone
        long t1 = System.currentTimeMillis();
        StringBuilder verficationVector = new StringBuilder();
        for (int i = 0; i<numberOfInputs; i++) {
            verficationVector.append(deltas[i]);
        }
        String verf = verficationVector.toString();
        verficationVector.insert(0,"%");
        connections.sendMessageToAll(verficationVector.toString());
        String[] wAll = connections.receiveMessage();
        for (String s : wAll) {
            if (!verf.equals(s))
                stop();
        }

        for (int i = 0; i < numberOfInputs; i++) {
            Gate g = circuit[i];
            g.setValue(deltas[i].subtract(input_randomness[i]));
            g.setComputed();
        }
        long t2 = System.currentTimeMillis();

        globalPid = pid;
    }

    private BigInteger Open(int degree, BigInteger share, int pid) {
        // int king = pid/2 % n;
        connections.sendToPi(0, share, pid);
        if (partyNr == 0) {
            BigInteger[] x_shares = connections.receive(pid);
            BigInteger x_king = ss.interpolation(degree, x_shares);
            connections.sendElementToAll(x_king, pid+1);
        }
        BigInteger x = connections.receiveFromPi(0, pid+1);
        return x;
    }

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


    // Used for opening a number of shares in parallel
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

    // adding value of wires
    public void add(Gate g) {
        // [x + y]
        Gate[] inputs = g.getIn();
        g.setValue(((inputs[0].getValue()).add(inputs[1].getValue())).mod(F));

        // [r · (x + y)]
        g.setVerf(((inputs[0].getVerf()).add(inputs[1].getVerf())).mod(F));

        g.setComputed();
    }
    // Same as CEPS_old
    private void multByConst(Gate g) {
        BigInteger constLabel = new BigInteger(Integer.toString(g.getLabel()));

        Gate input = (g.getIn()[0]);
        g.setValue((input.getValue().multiply(constLabel)).mod(F));

        g.setVerf((input.getVerf().multiply(constLabel)).mod(F));

        g.setComputed();
    }
    // Count of how many mult gates we have gone through
    // mult--

    private int mult_count = 0;
    public BigInteger mult(BigInteger v, BigInteger r, int pid, Triples[] triples) {
        lock.lock();
        Triples trip = triples[mult_count];
        mult_count++;
        lock.unlock();

        BigInteger a = trip.getA();
        BigInteger b = trip.getB();
        BigInteger c = trip.getC();

        BigInteger alpha_share = v.add(a);
        BigInteger beta_share = r.add(b);

        class openab extends Thread {
            private BigInteger share;
            private int pid;
            private BigInteger open_shares;

            private openab(BigInteger share, int pid) {
                this.share = share;
                this.pid = pid;
            }

            @Override
            public void run() {
                open_shares = Open(t, share, pid);
                synchronized (CGH18.this) {
                    CGH18.this.notify();
                }
            }
        }

        openab get_alpha = new openab(alpha_share, pid);
        openab get_beta = new openab(beta_share, pid+2);
        get_alpha.start();
        get_beta.start();

        try {
            get_alpha.join();
            get_beta.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        BigInteger alpha = get_alpha.open_shares;
        BigInteger beta = get_beta.open_shares;
        //  α β − α [b] − β [a] + [c].
        return ((((alpha.multiply(beta)).subtract( alpha.multiply(b) )).subtract(beta.multiply(a))).add(c)).mod(F);
    }


    // Computing triples used for evaluating multiplication gates.
    private Triples[] triples(int l, int pid) {
        int thisPid = pid;
        int randomSharesSend = (int) Math.ceil((double)l / (n-t));
        long t1 = System.currentTimeMillis();
        BigInteger[] a = random_old(l, thisPid);
        thisPid += randomSharesSend;
        BigInteger[] b = random_old(l, thisPid);
        thisPid += randomSharesSend;
        BigInteger[][] rR = doubleRandom_old(l, thisPid);
        thisPid += 2*randomSharesSend;
        BigInteger[] r = rR[0];
        BigInteger[] R = rR[1];
        long t2 = System.currentTimeMillis();

        t1 = System.currentTimeMillis();
        BigInteger[] d_shares = new BigInteger[l];
        for (int i = 0; i<l; i++) {
            d_shares[i] = (a[i].multiply(b[i])).add(R[i]).mod(F);
        }
        connections.setNrElementsToSend_OPEN(l);
        t1 = System.currentTimeMillis();
        BigInteger[] Ds = openParallel(2*t, d_shares, thisPid);
        connections.resetNrElementsToSend();
        t2 = System.currentTimeMillis();

        BigInteger[] c = new BigInteger[l];
        for (int i = 0; i<l; i++) {
            c[i] = Ds[i].subtract(r[i]).mod(F);
        }


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

    public void output(Gate g, int pid, int number) {
        BigInteger out = Open(t,(g.getIn()[0]).getValue(), pid);
/*
        lock.lock();
        System.out.println("Result " + number + " at " + partyNr + " :: " + out);
        lock.unlock();
*/
    }

    private void evaluateMults(int countMults, Gate[] mults, Triples[] multTriples) {
        BigInteger[] alphasAndBetas = new BigInteger[4*countMults];
        BigInteger[] as = new BigInteger[2*countMults];
        BigInteger[] bs = new BigInteger[2*countMults];
        BigInteger[] cs = new BigInteger[2*countMults];

        for (int k = 0; k < countMults; k++) {
            Gate g = mults[k];
            Gate[] ins = g.getIn();

            BigInteger x1 = ins[0].getValue();
            BigInteger x2 = ins[1].getValue();

            Triples trip = multTriples[mult_count];
            Triples trip_verf = multTriples[mult_count + numberOfMults];
            mult_count++;

            // Evaluating the circuit
            BigInteger a = trip.getA();
            as[k] = a;
            BigInteger b = trip.getB();
            bs[k] = b;
            cs[k] = trip.getC();
            alphasAndBetas[k] = x1.add(a);
            alphasAndBetas[k + countMults] = x2.add(b);

            // Evaluating the verification
            BigInteger verf1 = g.getIn()[0].getVerf();
            BigInteger a_verf = trip_verf.getA();
            as[k+countMults] = a_verf;
            BigInteger b_verf = trip_verf.getB();
            bs[k+countMults] = b_verf;
            cs[k+countMults] = trip_verf.getC();
            alphasAndBetas[k + 2*countMults] = verf1.add(a_verf);
            alphasAndBetas[k + 3*countMults] = x2.add(b_verf);
        }
        connections.setNrElementsToSend_OPEN(alphasAndBetas.length);
        BigInteger[] opens = openParallel(t, alphasAndBetas, globalPid);
        globalPid = 0;

        for (int k = 0; k < countMults; k++) {
            // Computing the value of the gates in the circuit.
            Gate g = mults[k];
            BigInteger alpha = opens[k];
            BigInteger beta = opens[k + countMults];
            g.setValue(((((alpha.multiply(beta)).subtract( alpha.multiply(bs[k]) )).subtract(beta.multiply(as[k]))).add(cs[k])).mod(F));

            // Computing the value of the gates in the verification circuit.
            BigInteger alpha_verf = opens[k + 2*countMults];
            BigInteger beta_verf = opens[k + 3*countMults];
            g.setVerf(((((alpha_verf.multiply(beta_verf)).subtract( alpha_verf.multiply(bs[k + countMults]) )).subtract(beta_verf.multiply(as[k + countMults]))).add(cs[k + countMults])).mod(F));

            g.setComputed();
        }
    }

    private BigInteger[] evaluateInputMults(int countMults, Gate[] inputs, Triples[] triples, BigInteger r) {
        BigInteger[] alphasAndBetas = new BigInteger[2*countMults];
        BigInteger[] as = new BigInteger[countMults];
        BigInteger[] bs = new BigInteger[countMults];
        BigInteger[] cs = new BigInteger[countMults];

        for (int k = 0; k < countMults; k++) {
            Gate g = inputs[k];
            BigInteger input = g.getValue();
            Triples trip = triples[mult_count];
            mult_count++;

            BigInteger a = trip.getA();
            as[k] = a;
            BigInteger b = trip.getB();
            bs[k] = b;
            cs[k] = trip.getC();
            alphasAndBetas[k] = input.add(a);
            alphasAndBetas[k + countMults] = r.add(b);
        }
        connections.setNrElementsToSend_OPEN(alphasAndBetas.length);
        BigInteger[] opens = openParallel(t, alphasAndBetas, globalPid);
        connections.resetNrElementsToSend();
        globalPid += 2*alphasAndBetas.length;

        BigInteger[] res = new BigInteger[countMults];
        for (int k = 0; k < countMults; k++) {
            // Computing the value of the gates in the circuit.
            BigInteger alpha = opens[k];
            BigInteger beta = opens[k + countMults];
            res[k] = ((((alpha.multiply(beta)).subtract( alpha.multiply(bs[k]) )).subtract(beta.multiply(as[k]))).add(cs[k])).mod(F);
        }

        return res;
    }




    @Override
    public long[] runProtocol(Gate[] circuit) {
        System.out.println("Running CGH18!");

        calcNumberOfInputs(circuit);
        calcNumberOfMultsAndOuts(circuit);

        // Share the inputs of the circuit using the appropriate actions
        long tstart = System.currentTimeMillis();

        long t1 = System.currentTimeMillis();
        inputSharing(circuit);
        long t2 = System.currentTimeMillis();
        System.out.println("input done! " + (t2-t1));
        // Generating radomizing share
        connections.resetNrElementsToSend();
        t1 = System.currentTimeMillis();
        BigInteger randomSharing = random_old(1, globalPid)[0];
        globalPid++;
        Triples[] inputTriples;
        mult_count = 0;
        globalPid+=4;

        // The input triples are generated here.
        int nrIns;
        Gate[] inputs;
        if (circuit[0].getIn() == null) {
            inputTriples = triples(numberOfInputs, globalPid);
            globalPid += 2*numberOfInputs + 4*(int) Math.ceil((double)numberOfInputs / (n-t));
            nrIns = numberOfInputs;
            inputs = Arrays.copyOfRange(circuit, 0, numberOfInputs);
        } else {
            inputTriples = triples(numberOfInputs+1, globalPid);
            globalPid += 2*(numberOfInputs+1) + 4*(int) Math.ceil((double)(numberOfInputs+1) / (n-t));
            inputs = Arrays.copyOfRange(circuit, 0, numberOfInputs+1);
            inputs[inputs.length - 1] = circuit[0].getIn()[0];
            nrIns = numberOfInputs+1;
        }

        BigInteger[] inputVerfs = evaluateInputMults(nrIns, inputs , inputTriples, randomSharing);

        for (int i = 0; i < numberOfInputs; i++) {
            circuit[i].setVerf(inputVerfs[i]);
        }
        if (circuit[0].getIn() != null)
            circuit[0].getIn()[0].setVerf(inputVerfs[numberOfInputs]);
        t2 = System.currentTimeMillis();

        // Triples for computing the mults
        t1 = System.currentTimeMillis();
        Triples[] multTriples = triples(2 * numberOfMults, globalPid);
        globalPid += 2*numberOfMults + 4*(int) Math.ceil((double)numberOfMults / (n-t));
        mult_count = 0;

        // Computation of circuit
        int currentLvl = 1;
        int[] idxOfMults = new int[numberOfMults];
        circuitLabel:
        for (int i = numberOfInputs; i<circuit.length;) {
            int countMultsThisLvl = 0;
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
                    countMultsThisLvl++;
                i++;
                if (i == circuit.length)
                    break;
            }
            //TODO maybe move this down
            if (countMultsThisLvl != 0)
                connections.setNrElementsToSend_OPEN(2*countMultsThisLvl);

            // Start to compute all the gates which are ready.
            Gate[] mults = new Gate[countMultsThisLvl];
            // Gate[] verfMults = new Gate[countMultsThisLvl];
            int multIterCounter = 0;
            for (int k=j; k<i; k++) {
                Gate g = circuit[k];
                Type type = g.getType();
                switch(type) {
                    case ADD:
                        add(g);
                        break;

                    case CONST:
                        multByConst(g);
                        break;

                    case MULT:
                        mults[multIterCounter] = g;
                        // verfMults[multIterCounter] = verf;
                        idxOfMults[multIterCounter] = k;
                        multIterCounter++;
                        break;

                    case OUTPUT:
                        break circuitLabel;

                    case INPUT:
                        System.out.println("EROOR");
                        break;
                }
            }
            currentLvl++;

            // Evaluating multiplication gates
            if (countMultsThisLvl != 0)
                evaluateMults(countMultsThisLvl, mults, multTriples);
        }
        t2 = System.currentTimeMillis();
        System.out.println("EVAL done! " + (t2-t1));

        // -Verification step
        connections.resetNrElementsToSend();
        t1 = System.currentTimeMillis();
        BigInteger[] coins = coin(numberOfMults + numberOfInputs);
        BigInteger u = BigInteger.ZERO;
        BigInteger w = BigInteger.ZERO;

        for (int i = 0; i < numberOfMults; i++) {
            u = u.add(coins[i].multiply(circuit[idxOfMults[i]].getVerf()));
            w = w.add(coins[i].multiply(circuit[idxOfMults[i]].getValue()));
        }
        int inNr = 0;
        for (int i = numberOfMults; i < numberOfMults+numberOfInputs; i++) {
            u = u.add(coins[i].multiply(circuit[inNr].getVerf()));
            w = w.add(coins[i].multiply(circuit[inNr].getValue()));
            inNr++;
        }
        u = u.mod(F);
        w = w.mod(F);

        // System.out.println("for loops " + (t2-t1));
        connections.resetNrElementsToSend();
        BigInteger r = Open(t, randomSharing, globalPid);
        globalPid+=2;
        // System.out.println("open verf " + (t1-t2));
        // [T] = [u] − r · [w].
        BigInteger Tshare = u.subtract(r.multiply(w)).mod(F);
        connections.resetNrElementsToSend();
        boolean check = checkZero(Tshare);
        if (!check) {
            System.out.println("BOTTOM");
            connections.sendMessageToAll("BOTTOM");
            return null;
        }
        t2 = System.currentTimeMillis();
        // System.out.println("check Zero " + (t2-t1));
        System.out.println("Verification done! " + (t2-t1));

        // Computing the outputs
        connections.setNrElementsToSend_OPEN(numberOfOutputs);
        int k = 0;
        BigInteger[] outputShares = new BigInteger[numberOfOutputs];
        for (int i = circuit.length - numberOfOutputs; i < circuit.length; i++) {
            outputShares[k] = (circuit[i].getIn()[0]).getValue();
            k++;
        }

        BigInteger[] res = openParallel(t, outputShares, globalPid);

        t1 = System.currentTimeMillis();

        long tend = System.currentTimeMillis();

        // Resetting fields if the protocol is to be runProtocol again!
        connections.resetNrElementsToSend();
        executor.shutdown();
        executor.isTerminated();
        executor = Executors.newFixedThreadPool(8);
        numberOfMyInputsEvaluated = 0;
        mult_count = 0;
        globalPid = 0;
        long durationProtocol = tend-tstart;
        int sharesSend = connections.nrOfSharesSend;
        System.out.println(partyNr + " shares send " + sharesSend);
        System.out.println(partyNr + " messages send " + connections.messagesSend);
        int messagesSend = connections.messagesSend;
        // System.out.println(partyNr + " Duration protocol " + durationProtocol);
        connections.nrOfSharesSend = 0;
        connections.messagesSend = 0;

        return new long[]{sharesSend, messagesSend, durationProtocol};
    }
    // TODO where is this
    /*– Inputs: The parties hold a sharing [v].
            – The protocol:
            1. The parties call F rand to obtain a sharing [r].
            2. The parties call F mult on [r] and [v] to obtain [T] = [r · v]
            3. The parties runProtocol open([T]). If a party receives ⊥, then it outputs ⊥. Else, it continues.
            4. Each party checks that T = 0. If yes, it outputs accept; else, it outputs reject*/

    private BigInteger[][] doubleRandom_old(int l, int pid) {
        int b = F.bitLength();
        // Generate doubleRandom si
        BigInteger si;
        // Check if si was generated inside the field.
        do {
            si = new BigInteger(b, sc);
        }
        while (si.compareTo(F) > 0);
        BigInteger[] tSharing = ss.secretSharing(t, si);
        BigInteger[] two_tSharing = ss.secretSharing(2*t, si);


        connections.sendShares(tSharing, pid);
        BigInteger[] tShares = connections.receive(pid);
        pid++;

        connections.sendShares(two_tSharing, pid);
        BigInteger[] two_tShares = connections.receive(pid);

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

    private BigInteger[] random_old(int l, int pid) {
        int b = F.bitLength();
        // Generate doubleRandom si
        BigInteger si;
        // Check if si was generated inside the field.
        do {
            si = new BigInteger(b, sc);
        } while (si.compareTo(F) > 0);

        BigInteger[] tSharing = ss.secretSharing(t,si);

        connections.sendShares(tSharing, pid);
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

    private boolean checkZero(BigInteger tshare) {
        BigInteger r = random_old(1, globalPid)[0];
        globalPid++;
        Triples[] triples = triples(1, globalPid);
        globalPid+=4;
        mult_count = 0;
        BigInteger multshares = mult(r, tshare, globalPid, triples);
        globalPid+=4;
        BigInteger res = Open(t, multshares, globalPid);
        globalPid+=2;

        return res.equals(BigInteger.ZERO);
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
            System.out.println("Something is wrong in gate ready!");
            return true;
        }
    }
    /*
        F coin is an ideal functionality that chooses a random element from F and hands
        it to all parties. A simple way to compute F coin is to use F rand to generate a
        random sharing and then open it.
    */
    private BigInteger[] coin(int l) {
        BigInteger[] rand = random_old(l, globalPid);
        globalPid += (int) Math.ceil((double)l / (n-t));

        connections.setNrElementsToSend_OPEN(l);
        return openParallel(t, rand, globalPid);
    }

    @Override
    public void setup() {
        System.out.println("setting up connections..");

        // Setup
        long startTime = System.nanoTime();
        /////////////////////////////////////////////////////////////////
        connections.setup();
        /////////////////////////////////////////////////////////////////
        long endTime = System.nanoTime();

        long duration_setup = (endTime - startTime)/1000000;
    }

    @Override
    public void stop() {
        connections.stop();
    }

}
























