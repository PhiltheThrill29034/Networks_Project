package Bidder;

import java.io.IOException;

public class B2B_Server_Thread extends Thread { // Το Thread που τρέχει το B2B_server στο παρασκήνιο, 
                                                // ώστε να μην κολλάει στην αναμονή της σύνδεσης των άλλων Bidder
    private int port;
    private String name;

    B2B_Server_Thread(int port, String name) {
        this.port = port;
        this.name = name;
    }

    @Override
    public void run() {

        try {
            B2B_Server b2bServer = new B2B_Server(port, name);
            b2bServer.start(); // Εκκινούμε τη διαδικασία της διαχείρισης των συνδέσεων μεταξύ των Bidders

        } catch(IOException e) {
            e.printStackTrace();
        }

    }

}
