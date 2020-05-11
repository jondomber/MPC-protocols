import java.math.BigInteger;

import static java.lang.Thread.sleep;

public class CEPS implements MPCProtocol {
    private int partyNr; // The party number of this instantiation
    private BigInteger F; // prime generating field F
    private BigInteger[] x; // inputs of party
    private int t; // Maximum number of corrupted parties allowed

    public ConnectionHolder connections; // Connections to the other parties
    private SecretSharing secretShare; // The secret sharing functionality
    private int globalPid = 0; // Counter to keep track of where we are in the circuit to stop concurrency problems.

    // Constructor
    CEPS(int partyNr, BigInteger F, BigInteger[] x, int n, String[] hostnames, int[] ports) {
        this.partyNr = partyNr;
        this.F = F;
        this.x = x;
        t = (int) Math.ceil( n/2.0-1 );

        // Instantiating the class which takes care of sending and receiving messages between parties.
        connections = new ConnectionHolder(n, partyNr, ports, hostnames);

        // Generating randomness
        secretShare = new SecretSharing(n, F);
    }

    // calculating the number of inputs and the number of OWN inputs in the circuit.
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

    private int numberOfInputs; // Variable for keeping the track of the number of inputs
    private int numberOfMyInputs; // The number of inputs this party is providing

    // Secret Sharing of the inputs of the circuit
    private void inputSharing(Gate[] circuit) {
        int j = 0; // Keeps track of how far along we are in the inputs gates
        int i = 0; // Keeps track of how far along we are in sharing out OWN inputs.

        // We are sending a Secret share of all our inputs.
        connections.setNrOfElementsToSend(numberOfMyInputs); // Setting the number of elements to send so that they can be send efficiently.

        int pid = globalPid;
        // Send our own shares
        while (true) {
            Gate g = circuit[j];
            if( !(g.getType().equals(Type.INPUT)) ) break;
            int label = g.label;
            if (partyNr == label) {
                BigInteger[] mySS = secretShare.secretSharing(t, x[i]);
                i++;
                connections.sendShares(mySS, pid);
            }
            j++;
            pid++;
        }
        j=0;
        // Receive the shares from all the parties
        while(true) {
            Gate g = circuit[j];
            if( !(g.getType().equals(Type.INPUT)) ) break; // Break if there are no more input gates
            int label = g.label;
            BigInteger sharesReceived = connections.receiveFromPi(label, globalPid); // Receive the share from the designated party
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
        int currentLvl = 1; // Current lvl -- 1 since the input layer is done.
        for (int i = numberOfInputs; i < circuit.length; ) {
            int numberOfMultiplicationGatesInLayer = 0; // Counter to keep track of the number of --multiplication-- gates
            int numberOfOutputGatesInLayer = 0;// Counter to keep track of the number of --output-- gates
            int j = i;

            // Iterate over the gates of the current level og the circuit
            while (circuit[i].getLevel() == currentLvl) {
                if (circuit[i].getType() == Type.MULT)
                    numberOfMultiplicationGatesInLayer++;
                if (circuit[i].getType() == Type.OUTPUT)
                    numberOfOutputGatesInLayer++;
                i++;
                if (i == circuit.length)
                    break;
            }

            // Computes the add and multiply by constant gates and adds multiply and output gates to a list
            // which is then evaluated in parallel later.
            Gate[] multiplicationGates = new Gate[numberOfMultiplicationGatesInLayer];
            Gate[] outputGates = new Gate[numberOfOutputGatesInLayer];
            int multiplicationCounter = 0;
            int outCounter = 0;
            // Evaluate the gates.
            for (int k = j; k < i; k++) {
                Gate g = circuit[k];
                Type type = g.getType();
                switch (type) {
                    case ADD:
                        g.setValue(add(g));
                        g.setComputed();
                        break;

                    case CONST:
                        g.setValue(multiplyByConstant(g));
                        g.setComputed();
                        break;

                    case MULT:
                        multiplicationGates[multiplicationCounter] = g;
                        multiplicationCounter++;
                        break;

                    case OUTPUT:
                        outputGates[outCounter] = g;
                        outCounter++;
                        break;

                    case INPUT:
                        System.out.println("ERROR");
                        break;
                }
            }
            currentLvl++; // Moving on to the next level of the circuit

            // If there is no mult gates we are done, else we have to compute their value.
            if (numberOfMultiplicationGatesInLayer == 0 && outCounter == 0)
                continue;

            // Output reconstruction
            if (outCounter > 0) {
                EvaluateOutputGates(numberOfOutputGatesInLayer, outCounter, outputGates);
                break;
            }

            // Evaluate multiplication gates
            EvaluateMultiplicationGates(numberOfMultiplicationGatesInLayer, multiplicationGates);
        }
    }

    // Evaluating addition gate.
    private BigInteger add(Gate g) {
        Gate[] inputs = g.getIn();
        return ((inputs[0].getValue()).add(inputs[1].getValue())).mod(F);
    }

    // Evaluating multiplication by constant gate.
    private BigInteger multiplyByConstant(Gate g) {
        BigInteger input = (g.getIn()[0]).getValue();

        BigInteger labelOfConstant = new BigInteger(Integer.toString(g.getLabel()));

        return (input.multiply(labelOfConstant)).mod(F);
    }


    // Evaluating output gates
    public void  EvaluateOutputGates(int numberOfOutputGatesInLayer, int outCounter, Gate[] outs) {
        connections.setNrOfElementsToSend(numberOfOutputGatesInLayer);
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
            res[k] = secretShare.interpolation(t, shares);
        }
    }

    //Evaluating Multiplication gates.
    public void EvaluateMultiplicationGates(int countMults, Gate[] mults) {
        connections.setNrOfElementsToSend(countMults);
        int pid = globalPid;
        for (Gate g : mults) {
            Gate[] inputs = g.getIn();
            // hi = [ab;fafb]_2t
            BigInteger hi = ((inputs[0].getValue()).multiply(inputs[1].getValue())).mod(F);
            //Secret share hi
            BigInteger[] shares = secretShare.secretSharing(t, hi);
            connections.sendShares(shares, pid);
            pid++;
        }
        for (Gate mult : mults) {
            //Get shares from other parties ([h(1)], [h(2)], ..., [h(n)])
            BigInteger[] shares_h = connections.receive(globalPid);
            globalPid++;
            // Use Lagrange interpolation to find result
            BigInteger res = secretShare.interpolation(t, shares_h);
            mult.setValue(res);
            mult.setComputed();
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

        // Variables for time testing
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

        System.out.println("Duration Preprocess: " + duration_preprocess + " ms");

        System.out.println("Computation phase");
        int evalSharesBefore = connections.nrOfSharesSend;
        startTime = System.nanoTime();
        /////////////////////////////////////////////////////////////////
        computationPhase(circuit);
        /////////////////////////////////////////////////////////////////
        endTime = System.nanoTime();
        int evalSharesAfter = connections.nrOfSharesSend;

        long duration_eval = (endTime - startTime)/1000000;
        System.out.println("Duration Eval: " + duration_eval + " ms");

        runtimes[0] = connections.nrOfSharesSend;
        runtimes[1] = connections.messagesSend;
        System.out.println(partyNr + " bytes send " + connections.bytesSend);
        System.out.println(partyNr + " shares send " + connections.nrOfSharesSend);
        System.out.println(partyNr + " messages send " + connections.messagesSend);

        // Reset for next run
        connections.bytesSend = 0;
        connections.time = 0;
        connections.messagesSend = 0;
        connections.nrOfSharesSend = 0;
        connections.resetNrElementsToSend();
        globalPid = 0;

        long duration_protocol = duration_preprocess + duration_eval;
        runtimes[2] = duration_protocol;

        System.out.println("\nDuration protocol: " + duration_protocol + " ms");
        System.out.println("END! \n######################################################\n\n");


        // Stopping the attempts to receive!
        return runtimes;
    }

}
