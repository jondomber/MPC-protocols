import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;

public class RunProtocol {
    private static BigInteger F;
    private static int n;
    private static int partyNr;
    private static Gate[] circuit;
    private static String[] hostnames;
    private static int numberOfInputs;


    public static void main(String[] args) {
        /*
        if(args.length == 0) {
            System.out.println("########################################## \nRunning the protocol takes three inputs: \n 1. This party's number " +
                    "\n 2. The number of the protocol to run (0 for CEPS, 1 for Damgård-Nielsen, 3 for CGH18)" +
                    "\n 3. The path of the description of the circuit to be evaluated");
            return;
        }
        */
        partyNr = Integer.parseInt(args[0]);
        String protocolString = args[1];

        ImportCircuit imp = new ImportCircuit();

        String circuitPath = "Test_Circuits\\adder_32bit.txt";

        circuit = imp.importCircuit(circuitPath);
        System.out.println(circuit.length);

        numberOfInputs = getNumberOfInputs();
        BigInteger[] x = getInputs();

        set_F_n();
        int[] ports = GeneratePortnumbers.getPortnumbers(n)[partyNr];
        setHostnames();


        MPCProtocol protocol;
        switch (protocolString) {
            case "0":
                protocol = new CEPS(partyNr, F, x, n, hostnames, ports);
                break;
            case "1":
                protocol = new DamgaardNielsen_DK(partyNr, F, x, n, hostnames, ports);
                break;
            default:
                protocol = new CGH18(partyNr, F, x, n, hostnames, ports);
                break;
        }

        protocol.setup();
        protocol.runProtocol(circuit);
        protocol.stop();
    }

    private static int getNumberOfInputs() {
        int i = 0;
        int j = 0;
        while (true) {
            if(circuit[i].getType().equals(Type.INPUT)) {
                if (circuit[i].getLabel() == partyNr) j++;
                i++;
            } else break;
        }
        return j;
    }

    private static BigInteger[] getInputs() {
        // Only P0 and P1 provides input
        if(partyNr == 0 || partyNr == 1) {
            BigInteger[] in = new BigInteger[numberOfInputs];
            try (BufferedReader br = new BufferedReader(new FileReader("Inputs\\inputs" + partyNr + ".txt"))) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < numberOfInputs; i++) {
                    in[i] = new BigInteger(br.readLine());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return in;
        }
        return new BigInteger[0];
    }

    private static void setHostnames() {
        hostnames = new String[n];
        try (BufferedReader br = new BufferedReader(new FileReader("Inputs/IPs.txt"))) {
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<n; i++) {
                hostnames[i] = br.readLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void set_F_n() {
        try (BufferedReader br = new BufferedReader(new FileReader("Inputs/F_n.txt"))) {
            F = new BigInteger(br.readLine());
            n = Integer.parseInt(br.readLine());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
