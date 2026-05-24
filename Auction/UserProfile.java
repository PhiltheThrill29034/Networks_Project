package Auction;

public class UserProfile {
    String name;
    String password;
    public int numAuctionsSeller = 0;
    public int numAuctionsBidder = 0;
    private double reputation_score = 1;

    UserProfile(String name, String password){
        this.name=name;
        this.password=password;
    }

    public String getName(){
        return this.name;
    }

    public String getPassword(){
        return this.password;
    }

    public void setSellerCount(int count){
        this.numAuctionsSeller=count;
    }

    public void setBidderCount(int count){
        this.numAuctionsBidder=count;
    }
    

    public int getSellerCount(){
        return this.numAuctionsSeller;
    }

    public int getBidderCount(){
         return this.numAuctionsBidder;
    }

    public void setReputationScore(double rep){
        this.reputation_score=rep;
    }

    public double getReputation(){
        return this.reputation_score;
    }
}
