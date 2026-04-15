package shared.models;

public class AuctionItem {
    String tokenId;
    String itemId;
    String desc;
    double startBid;
    int duration;

    public AuctionItem(String tokenId,String itemId, String desc, double startBid,int duration)
    {
        this.tokenId=tokenId;
        this.itemId=itemId;
        this.desc=desc;
        this.startBid=startBid;
        this.duration=duration;
    }
}
