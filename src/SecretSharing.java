import java.math.BigInteger;
import java.security.SecureRandom;

public class SecretSharing {
    int n;
    BigInteger F;

    public SecretSharing(int n, BigInteger F) {
        this.n = n;
        this.F = F;
    }

    // Secret sharing -- returns array of shares which can be reconstructed to the secret given degree+1 shares.
    public BigInteger[] secretSharing(int degree, BigInteger secret) {
        // We want to generate polynomial of degree degree

        // We first sample the degree of the shared polynomial doubleRandom values from F
        BigInteger[] a = new BigInteger[degree+1];
        SecureRandom sc = new SecureRandom();

        // Number of bits in elements of F
        int b = F.bitLength();

        // set the polynomial to be the secret at y=0.
        a[0] = secret;
        int i = 1;
        while (i <= degree) {
            a[i] = new BigInteger(b, sc);
            // Check if ai was generated inside the field.
            if (a[i].compareTo(F) <= 0) i ++;
        }


        // Computing the polynomial at each of the n points
        BigInteger[] ss = new BigInteger[n];
        for (int j = 1; j <= n; j++) {
            BigInteger[] xs = new BigInteger[degree+1];

            // We create an array consisting of (X^0, X^1, ..., X^degree)
            for (int k = 0; k <= degree; k++) {
                xs[k] = (new BigInteger(Integer.toString(j), 10)).modPow(new BigInteger(Integer.toString(k)), F);
            }

            // Multiply each coefficient by the value of X^i
            BigInteger[] terms = new BigInteger[degree+1];
            for (int k = 0; k <= degree; k++) {
                terms[k] = (xs[k].multiply(a[k])).mod(F);
            }

            // Add all the terms together
            BigInteger sum = new BigInteger("0");
            for (BigInteger c : terms)
                sum = sum.add(c);
            // Add the value we want to secret share
            ss[j-1] = sum.mod(F);
        }

        return ss;
    }

    // Computing the recombination vector
    private BigInteger[] recombination(int[] goodidx) {
        // recombination vector ri = (a + b + ... + m)((i - a)(i-b)...(i-c))^-1
        BigInteger[] r = new BigInteger[n];

        for (int i : goodidx) {
            BigInteger i_bi = (new BigInteger(Integer.toString(i+1), 10));
            BigInteger first = BigInteger.ONE;
            BigInteger second = BigInteger.ONE;
            for (int j : goodidx) {
                if(i == j) continue;
                BigInteger j_bi = (new BigInteger(Integer.toString(j+1), 10));
                first = first.multiply(j_bi);
                second = second.multiply( i_bi.subtract(j_bi) );
            }

            if((goodidx.length % 2) == 0) first = first.negate();
            first = first.mod(F);

            second = second.modInverse(F);
            // We multiply by i's inverse to get the first part of the sum and then multiply by the second part.
            r[i] = (first.multiply(second)).mod(F);
        }
        return r;
    }

    // Interpolation of shares to get the secret back.
    public BigInteger interpolation(int t, BigInteger[] shares) {
        int[] goodidxs = indexOfGoodShares(t, shares);
        BigInteger[] r = recombination(goodidxs);

        // Compute sum_i ri [h(i);fi]_t
        BigInteger sum = BigInteger.ZERO;
        for (int i : goodidxs) {
            sum = sum.add( r[i].multiply(shares[i]) );
        }

        return sum.mod(F);
    }

    // Finds the shares which has been set.
    private int[] indexOfGoodShares(int t, BigInteger[] shares) {
        int[] goodSharesidx = new int[t+1];
        int counter = 0;
        for (int i = 0; i<t+1; i++) {
            if (counter >= t+1) break;
            BigInteger s = shares[i];

            if (s == null) continue;
            goodSharesidx[counter] = i;
            counter++;
        }
        return goodSharesidx;
    }
}
