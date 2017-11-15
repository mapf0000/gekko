package de.gekko.websocketNew.pojo;

import java.math.BigDecimal;

import com.google.gson.annotations.SerializedName;

public class Order {
	
    @SerializedName("Quantity")
    protected BigDecimal quantity;
    
    @SerializedName("Rate")
    protected BigDecimal rate;
    
    @Override
    public String toString() {
        return "Order [quantity=" + quantity + ", rate=" + rate + "]";
    }
    
}