import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

class ImportCircuit {

    Gate[] importCircuit(String inputFile) {
        try (BufferedReader br = new BufferedReader(new FileReader(inputFile))) {
            String line = br.readLine();

            int numberOfGates = Integer.parseInt(line.substring(0, line.indexOf(" ")));
            // int numberOfWires = Integer.parseInt(line.substring(line.indexOf(" ") + 1));

            line = br.readLine();

            // Number of input gates from P1??
            int n1 = Integer.parseInt(line.substring(0, line.indexOf(" ")));
            line = line.substring(line.indexOf(" ") + 1);

            // Number of input gates from P2
            int n2 = Integer.parseInt(line.substring(0, line.indexOf(" ")));
            line = line.substring(line.indexOf(" ") + 1);

            // Number of output gates
            int n_out = Integer.parseInt(line.substring(line.lastIndexOf(" ") + 1));

            // TODO maybe we can handle the input gates in a different way
            // number of gates in the circuit is the input gates + 4 times the number of gates in the
            // circuit since each XOR gate results in 4 gates in this circuit!
            Gate[] circuit = new Gate[n1 + n2 + 4*numberOfGates + n_out];

            // Inputs of P0
            for (int i = 0; i < n1; i++) {
                circuit[i] = new Gate(Type.INPUT, 0);
                circuit[i].setLevel(0);
            }
            // Inputs of P1
            for (int i = n1; i < n1+n2; i++) {
                circuit[i] = new Gate(Type.INPUT, 1);
                circuit[i].setLevel(0);
            }

            // Use this to add with const -- but without making a new type of gate
            Gate const1 = new Gate(Type.INPUT, -1);
            const1.setValue(BigInteger.ONE);
            const1.setComputed();
            const1.setLevel(0);

            circuit[0].setIn(new Gate[]{const1});


            br.readLine();
            line = br.readLine();
            int[] mapWireToGate = new int[n1+n2+numberOfGates];
            for (int i = 0; i<n1+n2; i++) {
                mapWireToGate[i] = i;
            }
            // counts how many times have we gone through the loop.
            int counter = n1 + n2;
            while (line != null) {
                // System.out.println("c " + c);
                // There are empty lines at the end -- this is a check of some sorts.
                if(!line.contains(" ")) { break; }
                int nIn = Integer.parseInt(line.substring(0, line.indexOf(" ")));
                line = line.substring(line.indexOf(" ") + 1);

                int nOut = Integer.parseInt(line.substring(0, line.indexOf(" ")));
                line = line.substring(line.indexOf(" ") + 1);

                int v1 = Integer.parseInt(line.substring(0, line.indexOf(" ")));
                line = line.substring(line.indexOf(" ") + 1);
                int v2 = Integer.parseInt(line.substring(0, line.indexOf(" ")));
                line = line.substring(line.indexOf(" ") + 1);
                String type;
                if (!line.contains(" ")) {
                    // When this happens the gate is INV
                    line = line.substring(line.indexOf(" ") + 1);
                    type = line;
                    Gate g = circuit[mapWireToGate[v1]];
                    // inv(g) = -g + 1

                    // Multiply by -1
                    Gate g_neg = new Gate(Type.CONST, -1);
                    Gate[] ins_inv = {g};
                    g_neg.setIn(ins_inv);
                    circuit[counter] = g_neg;
                    counter++;

                    // Add together.
                    Gate g_res = new Gate(Type.ADD);
                    Gate[] ins_res = {const1, g_neg};
                    g_res.setIn(ins_res);
                    circuit[counter] = g_res;
                    mapWireToGate[v2] = counter;

                } else {
                    int v3 = Integer.parseInt(line.substring(0, line.indexOf(" ")));
                    line = line.substring(line.indexOf(" ") + 1);
                    type = line;
                    Gate g1 = circuit[mapWireToGate[v1]];
                    Gate g2 = circuit[mapWireToGate[v2]];
                    if (type.equals("AND")) {
                        Type t = Type.MULT;
                        Gate g = new Gate(t);
                        Gate[] ins = {g1, g2};
                        g.setIn(ins);
                        circuit[counter] = g;
                        mapWireToGate[v3] = counter;
                    }
                    else if (type.equals("XOR")) {
                        // System.out.println("XOR");
                        // To compute w1 XOR w2 in the field F_p we need to compute w1+w2 - w1*w2

                        // add b1 and b2
                        Gate g_add = new Gate(Type.ADD);
                        Gate[] ins_add = {g1, g2};
                        g_add.setIn(ins_add);
                        circuit[counter] = g_add;
                        counter++;

                        // multiply b1 and b2
                        Gate g_mult = new Gate(Type.MULT);
                        Gate[] ins_mult = {g1, g2};
                        g_mult.setIn(ins_mult);
                        circuit[counter] = g_mult;
                        counter++;

                        // Multiply by -2 to get ready to subtract
                        Gate g_sub = new Gate(Type.CONST, -2);
                        Gate[] ins_sub = {g_mult};
                        g_sub.setIn(ins_sub);
                        circuit[counter] = g_sub;
                        counter++;


                        // Add together.
                        Gate g_res = new Gate(Type.ADD);
                        Gate[] ins_res = {g_add, g_sub};
                        g_res.setIn(ins_res);
                        circuit[counter] = g_res;
                        mapWireToGate[v3] = counter;
                    } else {
                        System.out.println("ERROR!");
                    }
                }
                line = br.readLine();
                counter++;
            }
            int count_outs = 1;
            // Here we are setting the ouput gates to the last n3 gates of the circuit.
            for (int i = counter + n_out - 1; i >= counter; i--) {
                Gate[] g_in = {circuit[mapWireToGate[n1+n2+numberOfGates - count_outs]]};
                Gate g_output = new Gate(Type.OUTPUT);
                g_output.setIn(g_in);
                circuit[i] = g_output;
                count_outs++;
            }

            // Trim the array
            circuit = Arrays.copyOfRange(circuit, 0, counter + n_out);
            setLevels(circuit);
            return circuit;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new Gate[0];
    }

    private void setLevels(Gate[] circuit) {
        int largestLvl = 0;
        for (Gate g : circuit) {
            int lvl = 0;
            // Input gate
            if (g.getType().equals(Type.INPUT)) continue;
            //  Output
            if (g.getType().equals(Type.OUTPUT)) {
                lvl = largestLvl;
            } else {
                for (Gate in : g.getIn()) {
                    if (in.getLevel() > lvl)
                        lvl = in.getLevel();
                }
                if (lvl+1 > largestLvl)
                    largestLvl = lvl + 1;
            }
            int res = lvl +1;
            g.setLevel(res);
        }
        Arrays.sort(circuit);
    }


}
