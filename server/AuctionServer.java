package server;

import java.net.ServerSocket;
import java.net.Socket;

public class AuctionServer{

    public static void main (String[] args){
        AuctionServer server = new AuctionServer();
        server.startLoop();

    }

    private void startLoop(){

        try (ServerSocket ss = new ServerSocket(5000)){
            Socket clientSocket = ss.accept();
            
        }
    }

    
}