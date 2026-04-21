package Bidder;

import java.net.Socket;

public class B2B_Connection_Thread extends Thread { // Τα Thread που εξηπυρετούν τα διάφορα αιτήματα
                                                    // που προέρχονται από την σύνδεση των 2 Bidder  
     
    Socket auctionSocket;

    B2B_Connection_Thread(Socket auctionSocket) {
        this.auctionSocket = auctionSocket;
    }

    @Override
    public void run() {

    }

}
