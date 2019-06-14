import java.util.PriorityQueue;
import java.util.Queue;

public class GeneratePortnumbers {
    public static int[][] getPortnumbers(int n) {
        int port0 = 10000;
        // int[] portNr = new int[(n*(n-1))/2];
        Queue<Integer> portNr = new PriorityQueue<>();
        for (int i = 0; i < (n * (n+1)) / 2; i++) {
            portNr.add(port0+i);
        }
        int[][] res = new int[n][n];
        for (int i = 0; i<n; i++) {
            for (int j = 0; j<n; j++) {
                int move = i-j;
                if(move < 0 || move >= n)
                    move = 0;
                // THE port number is never 0 so it works as null
                if(res[i-move][j+move] == 0)
                    res[i][j] = portNr.remove();
                else
                    res[i][j] = res[i-move][j+move];
            }
        }
        return res;
    }
}
