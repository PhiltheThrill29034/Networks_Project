package Bidder;

public class Bidder_Producer { // Παράγει στην εκκίνηση του συστήματος τους συγκεκριμένους Bidder που θα χρησιμοποιήσουμε

    public static void main(String[] args) {

        String[] usernames = {"Gerasimos", "Filippos", "Alexandra", "Kyriakos", "Nikos"};

        int basePort = 6001;

        for (int i = 0; i < usernames.length; i++) {

            int port = basePort + i;

            String username = usernames[i];

            new Thread(() -> {
                try {
                    Bidder bidder = new Bidder(port, username, "password1234");
                    bidder.startBidder();
                } catch (Exception e) {
                    System.err.println("Failed to start bidder " + username + " on port " + port);
                    e.printStackTrace();
                }
            }).start();
        }
    }

}
