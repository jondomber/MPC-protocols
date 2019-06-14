import java.math.BigInteger;

import static java.lang.Thread.sleep;

public class CEPS implements MPCProtocol {
    private int partyNr;
    private BigInteger F; // prime generating field F
    private BigInteger[] x; // inputs of party
    private int t; // Maximum number of corrupted parties


    public ConnectionHolder connections;
    private SecretSharing ss;
    private int globalPid = 0;


    CEPS(int partyNr, BigInteger F, BigInteger[] x, int n, String[] hostnames, int[] ports) {
        this.partyNr = partyNr;
        this.F = F;
        this.x = x;
        t = (int) Math.ceil( n/2.0-1 );

        // Instantiating the class which takes care of sending and receiving messages between parties.
        connections = new ConnectionHolder(n, partyNr, ports, hostnames);

        // Generating randomness TODO.
        ss = new SecretSharing(n, F);
    }

    // calculating the number of inputs in the circuit.
    private void calcNumberOfInputs(Gate[] circuit) {
        int i = 0;
        int j = 0;
        while (true) {
            if(circuit[i].getLabel() == partyNr) j++;
            if(circuit[i].getType().equals(Type.INPUT)) i++;
            else break;
        }
        numberOfInputs = i;
        numberOfMyInputs =  j;
    }


    // Evaluating addition gate
    private BigInteger add(Gate g) {
        Gate[] inputs = g.getIn();
        return ((inputs[0].getValue()).add(inputs[1].getValue())).mod(F);
    }

    private BigInteger multByConst(Gate g) {
        BigInteger input = (g.getIn()[0]).getValue();
        if (input == null)
            System.out.println(9);
        BigInteger constLabel = new BigInteger(Integer.toString(g.getLabel()));

        return (input.multiply(constLabel)).mod(F);
    }

    private int numberOfInputs;
    private int numberOfMyInputs;
    // Sharing of the inputs of the circuit
    private void inputSharing(Gate[] circuit) {
        // Keeping track of how far along we are in sharing out OWN inputs.
        int j = 0;
        int i = 0;

        // We are sending a ss of all our inputs.
        connections.setNrElementsToSend(numberOfMyInputs);

        // SS all input gates
        int pid = globalPid;
        while (true) {
            Gate g = circuit[j];
            if( !(g.getType().equals(Type.INPUT)) ) break;
            int label = g.label;
            if (partyNr == label) {
                BigInteger[] mySS = ss.secretSharing(t, x[i]);
                i++;
                connections.sendShares(mySS, pid);
            }
            j++;
            pid++;
        }
        j=0;
        while(true) {
            Gate g = circuit[j];
            if( !(g.getType().equals(Type.INPUT)) ) break;
            int label = g.label;
            BigInteger sharesReceived = connections.receiveFromPi(label, globalPid);
            globalPid++;
            circuit[j].setValue(sharesReceived);
            circuit[j].setComputed();
            j++;
        }
        connections.resetNrElementsToSend();
    }

    private void computationPhase(Gate[] circuit) {
        /*
           Iterate over all gates in the circuit.
           We assume that the gates of the circuit are in computational order.
           We evaluate the gates in parallel when they are ready, i.e. their input gates has been evaluated.
        */
        int currentLvl = 1;
        for (int i = numberOfInputs; i < circuit.length; ) {
            int countMults = 0;
            int countOuts = 0;
            int j = i;

            while (circuit[i].getLevel() == currentLvl) {
                if (circuit[i].getType() == Type.MULT)
                    countMults++;
                if (circuit[i].getType() == Type.OUTPUT)
                    countOuts++;
                i++;
                if (i == circuit.length)
                    break;
            }

            // Computes the add and multiply by constant gates and adds multiply and output gates to a list
            // which is then evaluated in parallel later.
            Gate[] mults = new Gate[countMults];
            Gate[] outs = new Gate[countOuts];
            int multCounter = 0;
            int outCounter = 0;
            for (int k = j; k < i; k++) {
                Gate g = circuit[k];
                Type type = g.getType();
                switch (type) {
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
            if (countMults == 0 && outCounter == 0)
                continue;

            // Output reconstruction
            if (outCounter > 0) {
                connections.setNrElementsToSend(countOuts);
                int pid = globalPid;
                for (int k = 0; k < outCounter; k++) {
                    Gate g = outs[k];
                    BigInteger share = ((g.getIn())[0]).getValue();
                    connections.sendElementToAll(share, pid);
                    pid++;
                }
                BigInteger[] res = new BigInteger[outCounter];
                for (int k = 0; k < outCounter; k++) {
                    BigInteger[] shares = connections.receive(globalPid);
                    globalPid++;
                    // output of the circuit
                    res[k] = ss.interpolation(t, shares);
                }
                break;
            }
            // Computing mult gates
            connections.setNrElementsToSend(countMults);
            int pid = globalPid;
            for (Gate g : mults) {
                Gate[] inputs = g.getIn();
                // hi = [ab;fafb]_2t
                BigInteger hi = ((inputs[0].getValue()).multiply(inputs[1].getValue())).mod(F);
                //Secret share hi
                BigInteger[] shares = ss.secretSharing(t, hi);
                connections.sendShares(shares, pid);
                pid++;
            }
            for (Gate mult : mults) {
                //Get shares from other parties ([h(1)], [h(2)], ..., [h(n)])
                BigInteger[] shares_h = connections.receive(globalPid);
                globalPid++;
                // Use Lagrange interpolation to find result
                BigInteger res = ss.interpolation(t, shares_h);
                mult.setValue(res);
                mult.setComputed();
            }
        }
    }

    // setting up connections with the other parties
    @Override
    public void setup() {
        System.out.println("Setting up connections");

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
    public void stop() {
        connections.stop();
    }

    @Override
    // Run the protocol on the specified circuit!
    public long[] runProtocol(Gate[] circuit) {
        System.out.println("Running CEPS");

        long startTime;
        long endTime;
        long[] runtimes = new long[3];

        // Preprocess
        calcNumberOfInputs(circuit);
        System.out.println("Input Sharing");
        startTime = System.nanoTime();
        /////////////////////////////////////////////////////////////////
        inputSharing(circuit);
        /////////////////////////////////////////////////////////////////
        endTime = System.nanoTime();

        long duration_preprocess = (endTime - startTime)/1000000;
        // runtimes[0] = duration_preprocess;

        System.out.println("Duration Preprocess: " + duration_preprocess + " ms");

        System.out.println("Computation phase");
        int evalSharesBefore = connections.nrOfSharesSend;
        startTime = System.nanoTime();
        /////////////////////////////////////////////////////////////////
        computationPhase(circuit);
        /////////////////////////////////////////////////////////////////
        endTime = System.nanoTime();
        int evalSharesAfter = connections.nrOfSharesSend;
        // System.out.println(partyNr + " eval shares " + (evalSharesAfter-evalSharesBefore));

        long duration_eval = (endTime - startTime)/1000000;
        /*runtimes[1] = duration_eval;
        runtimes[2] = connections.bytesSend;*/
        System.out.println("Duration Eval: " + duration_eval + " ms");

        /*runtimes[3] = connections.bytesSend;
        runtimes[4] = connections.time;*/
        runtimes[0] = connections.nrOfSharesSend;
        runtimes[1] = connections.messagesSend;
        System.out.println(partyNr + " bytes send " + connections.bytesSend);
        System.out.println(partyNr + " shares send " + connections.nrOfSharesSend);
        System.out.println(partyNr + " messages send " + connections.messagesSend);

        connections.bytesSend = 0;
        connections.time = 0;
        connections.messagesSend = 0;
        connections.nrOfSharesSend = 0;
        connections.resetNrElementsToSend();
        System.out.println("global pid " + globalPid);
        globalPid = 0;

        long duration_protocol = duration_preprocess + duration_eval;
        runtimes[2] = duration_protocol;

        System.out.println("\nDuration protocol: " + duration_protocol + " ms");
        System.out.println("END! \n######################################################\n\n");


        // Stopping the attempts to receive!
        return runtimes;
    }

}
