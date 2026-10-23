package com.pokedaisy.app.threeds;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only ORAS live-slot decoder. Addresses still require device verification. */
final class Gen6Party {
    static final int STRIDE=484, SLOTS=6, STATS=344;
    private static final String[] ORDERS={"ABCD","ABDC","ACBD","ACDB","ADBC","ADCB","BACD","BADC","BCAD","BCDA","BDAC","BDCA","CABD","CADB","CBAD","CBDA","CDAB","CDBA","DABC","DACB","DBAC","DBCA","DCAB","DCBA"};
    static final class Member {
        final int species,level,hp,maxHp,item; final int[] moves,pp; final String nickname;
        Member(byte[] b,byte[] stats){species=u16(b,8);item=u16(b,10);level=stats[4]&255;hp=u16(stats,8);maxHp=u16(stats,10);moves=new int[4];pp=new int[4];for(int i=0;i<4;i++){moves[i]=u16(b,0x5a+i*2);pp[i]=b[0x62+i]&255;}String n=new String(b,0x40,26,StandardCharsets.UTF_16LE);int end=n.indexOf(0);nickname=(end<0?n:n.substring(0,end)).replaceAll("[\\p{Cntrl}]","");}
    }
    static int u16(byte[] b,int o){return (b[o]&255)|((b[o+1]&255)<<8);}
    private static long u32(byte[] b,int o){return (u16(b,o)|(long)u16(b,o+2)<<16)&0xffffffffL;}
    private static void crypt(byte[] b,int start,int length,long seed){for(int i=start;i<start+length;i+=2){seed=(seed*0x41c64e6dL+0x6073)&0xffffffffL;int v=u16(b,i)^(int)(seed>>>16);b[i]=(byte)v;b[i+1]=(byte)(v>>>8);}}
    static Member decode(byte[] raw,int off){
        if(raw==null||off<0||off>raw.length-STATS-22)return null;
        byte[] b=Arrays.copyOfRange(raw,off,off+232),stats=Arrays.copyOfRange(raw,off+STATS,off+STATS+22);
        if(u16(b,4)!=0)return null;
        long seed=u32(b,0);crypt(b,8,224,seed);crypt(stats,0,22,seed);
        int sum=0;for(int i=8;i<232;i+=2)sum+=u16(b,i);if((sum&65535)!=u16(b,6))return null;
        String order=ORDERS[(int)((seed>>>13)&31)%24];byte[] shuffled=b.clone();for(int i=0;i<4;i++)System.arraycopy(shuffled,8+order.indexOf((char)('A'+i))*56,b,8+i*56,56);
        int species=u16(b,8),level=stats[4]&255,hp=u16(stats,8),maxHp=u16(stats,10);
        if(species<1||species>721||level<1||level>100||maxHp<1||maxHp>999||hp>maxHp||(b[0x1c]&255)>24)return null;
        for(int i=0;i<4;i++)if(u16(b,0x5a+i*2)>621||(b[0x62+i]&255)>64)return null;
        return new Member(b,stats);
    }
    static String describe(byte[] raw){
        if(raw==null)return "NEW · LIVE PARTY EXPERIMENTAL\nMemory bridge unavailable. Android gameplay still needs verification.";
        StringBuilder out=new StringBuilder("NEW · LIVE PARTY EXPERIMENTAL\nValidated memory slots (party count not yet mapped):");int valid=0;
        for(int i=0;i<SLOTS;i++){Member m=decode(raw,i*STRIDE);if(m!=null){valid++;out.append("\n").append(i+1).append(". ").append(m.nickname.isEmpty()?"Species #"+m.species:m.nickname).append(" · Lv ").append(m.level).append(" · HP ").append(m.hp).append('/').append(m.maxHp);}}
        if(valid==0)return "NEW · LIVE PARTY EXPERIMENTAL\nWaiting for checksum-valid data. The address has not been verified for this game revision.";
        return out.toString();
    }
}
