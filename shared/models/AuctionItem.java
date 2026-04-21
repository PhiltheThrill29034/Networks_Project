package shared.models;

public class AuctionItem {
    private String tokenId;
    private String itemId;
    private String desc;
    private double highestBid;
    private int duration;

    public AuctionItem(String tokenId,String itemId, String desc, double startBid, int duration)
    {
        this.tokenId = tokenId;
        this.itemId = itemId;
        this.desc = desc;
        this.highestBid = startBid;
        this.duration = duration;
    }

    public String getSellerTokenId() {
        return this.tokenId;
    }

    public String getObjectId() {
        return this.itemId;
    }

    public String getDescription() {
        return this.desc;
    }

    public double getHighestBid() {
        return this.highestBid;
    }

    public void setHighestBid(double bid) {
        this.highestBid = bid; 
    }

    public int getDuration() {
        return duration;
    }

}
