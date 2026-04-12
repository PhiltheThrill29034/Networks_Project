package server;

public class UserProfile {
    String name;
    String password;
    public int numAuctionsSeller = 0;
    public int numAuctionsBidder = 0;

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
}
