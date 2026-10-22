package com.pokedaisy.app.threeds;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
public class ThreeDsCartHeaderTest {
    static void put32(byte[] b,int o,int v){for(int i=0;i<4;i++)b[o+i]=(byte)(v>>>(i*8));}
    static void magic(byte[] b,int o,String s){System.arraycopy(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,o,s.length());}
    static byte[] cxi(){byte[] b=new byte[1024];magic(b,0x100,"NCCH");put32(b,0x104,2);b[0x18f]=4;magic(b,0x150,"CTR-P-ECRA");return b;}
    static String read(byte[] b)throws IOException{Path p=Files.createTempFile("3ds-header-", ".bin");try{Files.write(p,b);return ThreeDsCartHeader.validate(p.toFile());}finally{Files.delete(p);}}
    static void reject(byte[] b)throws Exception{try{read(b);fail("Invalid cartridge must be rejected");}catch(IOException expected){assertFalse(expected.getMessage().isEmpty());}}
    @Test public void acceptsUnencryptedNcch()throws Exception{assertEquals("CTR-P-ECRA",read(cxi()));}
    @Test public void acceptsUnencryptedNcsd()throws Exception{byte[] b=new byte[1536];magic(b,0x100,"NCSD");put32(b,0x120,1);put32(b,0x124,2);System.arraycopy(cxi(),0,b,512,1024);assertEquals("CTR-P-ECRA",read(b));}
    @Test public void rejectsOtherContainersAndTruncation()throws Exception{reject(new byte[12]);reject(new byte[1024]);byte[] b=cxi();put32(b,0x104,3);reject(b);}
    @Test public void rejectsEncryption()throws Exception{byte[] b=cxi();b[0x18f]=0;reject(b);}
    @Test public void rejectsOutOfRangePartition()throws Exception{byte[] b=new byte[1024];magic(b,0x100,"NCSD");put32(b,0x120,-1);put32(b,0x124,2);reject(b);put32(b,0x120,1);reject(b);}
}
