import java.math.BigInteger;

public class Gate implements Comparable<Gate>{

    Type type;
    private BigInteger value;
    int label;
    Gate[] in;

    int getLevel() {
        return level;
    }

    void setLevel(int level) {
        this.level = level;
    }

    private int level = -1;

    // For the verification part of the actively secure protocol.
    BigInteger getVerf() {
        return verf;
    }

    void setVerf(BigInteger verf) {
        this.verf = verf;
    }

    private BigInteger verf;

    /*
    Constructor if the type is mult by const or input where the label either refers to the party which
    should provide the input or the value of the constant in the constatant gate.
    A bit of overloading of a variable -- but it is fine
    */
    public Gate(Type type, int label) {
        this.type = type;
        this.label = label;
    }
    // Constructor otherwise -- set label to the min integer.
    public Gate(Type type) {
        this(type, Integer.MIN_VALUE);
    }

    boolean getComputed() {
        return isComputed;
    }

    void setComputed() {
        isComputed = true;
    }
    private void setComputed(boolean b) {
        isComputed = b;
    }

    private boolean isComputed = false;

    private void setLabel(int i) {
        label = i;
    }


    // Getters and setters
    BigInteger getValue() {
        return value;
    }

    public void setValue(BigInteger value) {
        this.value = value;
    }
    public Type getType() {
        return type;
    }

    public int getLabel() {
        return label;
    }

    public Gate[] getIn() {
        return in;
    }

    public void setIn(Gate[] in) {
        this.in = in;
    }

/*    @Override
    public Gate clone() {
        Gate clone = new Gate(this.getType());
        clone.setLabel(this.label);
        clone.setLevel(this.level);
        clone.setValue(this.value);
        clone.setComputed(this.isComputed);
        return clone;
    }*/

    @Override
    public int compareTo(Gate o) {
        return Integer.compare(this.getLevel(), o.getLevel());
    }
}
