package com.pokedaisy.app.threeds;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class Gen6PartyTest {
    private static final String[] ORDERS={"ABCD","ABDC","ACBD","ACDB","ADBC","ADCB","BACD","BADC","BCAD","BCDA","BDAC","BDCA","CABD","CADB","CBAD","CBDA","CDAB","CDBA","DABC","DACB","DBAC","DBCA","DCAB","DCBA"};
    static void word(byte[] b,int o,int v){b[o]=(byte)v;b[o+1]=(byte)(v>>>8);}
    static void crypt(byte[] b,int start,int length,long seed){for(int i=start;i<start+length;i+=2){seed=(seed*0x41c64e6dL+0x6073)&0xffffffffL;word(b,i,Gen6Party.u16(b,i)^(int)(seed>>>16));}}
    static byte[] fixture(int shuffle,int hp,int level){
        byte[] b=new byte[Gen6Party.STRIDE];long ec=((long)shuffle<<13)|123;
        for(int i=0;i<4;i++)b[i]=(byte)(ec>>>(i*8));
        word(b,8,258);word(b,10,1);b[0x1c]=13;
        byte[] name="TESTMON".getBytes(StandardCharsets.UTF_16LE);System.arraycopy(name,0,b,0x40,name.length);
        word(b,0x5a,33);word(b,0x5c,45);b[0x62]=35;b[0x63]=40;
        int sum=0;for(int i=8;i<232;i+=2)sum+=Gen6Party.u16(b,i);word(b,6,sum);
        byte[] canonical=b.clone();String order=ORDERS[shuffle%24];for(int i=0;i<4;i++)System.arraycopy(canonical,8+i*56,b,8+order.indexOf((char)('A'+i))*56,56);
        b[Gen6Party.STATS+4]=(byte)level;word(b,Gen6Party.STATS+8,hp);word(b,Gen6Party.STATS+10,21);
        crypt(b,8,224,ec);crypt(b,Gen6Party.STATS,22,ec);return b;
    }
    @Test public void decodesAllShuffleValuesAndSeparateStatsSeed(){for(int s=0;s<32;s++){Gen6Party.Member m=Gen6Party.decode(fixture(s,17,5),0);assertNotNull("shuffle "+s,m);assertEquals(258,m.species);assertEquals("TESTMON",m.nickname);assertEquals(5,m.level);assertEquals(17,m.hp);assertEquals(21,m.maxHp);assertEquals(1,m.item);assertArrayEquals(new int[]{33,45,0,0},m.moves);}}
    @Test public void rejectsCorruptEmptyAndTruncatedSlots(){byte[] b=fixture(7,17,5);b[90]^=1;assertNull(Gen6Party.decode(b,0));assertNull(Gen6Party.decode(new byte[484],0));assertNull(Gen6Party.decode(new byte[350],0));assertNull(Gen6Party.decode(null,0));assertNull(Gen6Party.decode(b,-1));}
    @Test public void rejectsImpossibleStatsButAllowsFainted(){assertNull(Gen6Party.decode(fixture(0,22,5),0));assertNull(Gen6Party.decode(fixture(0,1,0),0));assertNull(Gen6Party.decode(fixture(0,1,101),0));assertEquals(0,Gen6Party.decode(fixture(0,0,5),0).hp);}
    @Test public void readsEachStrideWithoutInventingPartyCount(){byte[] slots=new byte[6*484];System.arraycopy(fixture(3,17,5),0,slots,2*484,484);assertNull(Gen6Party.decode(slots,0));assertNotNull(Gen6Party.decode(slots,2*484));String text=Gen6Party.describe(slots);assertTrue(text.contains("3. TESTMON"));assertTrue(text.contains("party count not yet mapped"));assertFalse(text.contains("1. TESTMON"));}
}
