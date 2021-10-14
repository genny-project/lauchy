package org.acme.kafka.streams.aggregator.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Attribute {

    public int id;
    public String code;
    public String name;

    public Attribute(){
    }

    public Attribute(int id, String code,String name){
        this.id = id;
        this.code = code;
        this.name = name;
    }
    
 
    public String tidy(String dataStr) {
    		dataStr = dataStr.replaceAll("Adamm", "Adam");
        	return dataStr;

    }
    
    public Boolean validate(String dataStr) {
        if (dataStr.contains("Adaam")) {
        	return false;
        }
        return true;
    }

}
