package Bidder;

public class Bidder_Producer { // Παράγει στην εκκίνηση του συστήματος τους συγκεκριμένους Bidder που θα χρησιμοποιήσουμε

        public static void main(String[] args) {
            String[] usernames = {"Gerasimos", "Filippos", "Alexandra", "Kyriakos", "Nikos"};
            int baseBidderPort = 6000;
            int i = 1;
            
            for (String username : usernames) {
                final int bidderPort = baseBidderPort + i;

                new Thread(() -> { // To υλοποιούμε με Thread, για να δουλεύει ο καθένας ανεξάρτητα
                    Bidder bidder = new Bidder(bidderPort, username, "password1234"); // Ο Bidder ξεκινάει μόνος του από το startBidder(), μέσα στο constructor του 
                    bidder.startBidder();
                }).start();
                
                i++;
            }

        }

}
