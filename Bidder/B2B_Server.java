package Bidder;

import java.io.IOException;

import java.net.DatagramSocket;
import java.net.DatagramPacket;

public class B2B_Server { // Είναι ο server που περιμένει συνδέσεις απο άλλους Bidder
 
    private int port;
    private String name;

    B2B_Server(int port, String name) {
        this.port = port;
        this.name = name;
    }

    @SuppressWarnings("resource")
    public void start() throws IOException {
        
        // UDP Server
        DatagramSocket udpSocket = new DatagramSocket(port);
        System.out.println("[B2B_Server] " + name + "'s server is listening on port " + port);

        while(true) {

            // Σε αντίθεση με το TCP, το UDP δεν έχει streams, άρα πρέπει να δεσμεύουμε χώρο στη RAM για το πακέτο. 
            // Αυτό γίνεται με το receiveBuffer, το οποίο δεσμεύει 1024 bytes (1 KB)
            byte[] requestBuffer = new byte[1024];
            
            // Προετοιμασία του πακέτου, ώστε να μπορεί αυτό να δεχθεί τα data
            DatagramPacket requestPacket = new DatagramPacket(requestBuffer, requestBuffer.length);

            // Παραλαβή του request (πακέτου). Περιμένει εδώ μέχρι να σταλθεί το πακέτο
            udpSocket.receive(requestPacket);

            // Εκκινούμε τη διαδικασία της αγοράς του object
            new B2B_Connection_Thread(requestPacket, name).start();
        }
    }

}
