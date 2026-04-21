package Bidder;

import java.io.IOException;

import java.net.Socket;
import java.net.ServerSocket;

public class B2B_Server { // Είναι ο server που περιμένει συνδέσεις απο άλλους Bidder
 
    private int port;
    private String name;

    B2B_Server(int port, String name) {
        this.port = port;
        this.name = name;
    }

    @SuppressWarnings("resource") // Το χρησιμοποιούμε για να «κρύψουμε» το warning του server για το γεγονός ότι δεν το κλείνουμε ποτέ (resource leak)
    public void start() throws IOException {
        
        ServerSocket server = new ServerSocket(port);

        while(true) {
            Socket auctionSocket = server.accept();
            new B2B_Connection_Thread(auctionSocket, name).start();
        }
    }

}
