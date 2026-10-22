package com.pokedaisy.app.threeds;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Cartridge container validation before a file is committed to private storage. No keys or decryption. */
public final class ThreeDsCartHeader {
    private static long u32(byte[] b,int o){return (b[o]&255L)|((b[o+1]&255L)<<8)|((b[o+2]&255L)<<16)|((b[o+3]&255L)<<24);}
    private static String text(byte[] b,int o,int n){return new String(b,o,n,StandardCharsets.US_ASCII).replace("\0","");}
    public static String validate(File file)throws IOException{
        try(RandomAccessFile f=new RandomAccessFile(file,"r")){
            if(f.length()<512)throw new IOException("This file is too short to be a 3DS cartridge.");
            byte[] h=new byte[512];f.readFully(h);String magic=text(h,0x100,4);long start=0;
            if(magic.equals("NCSD")){
                start=u32(h,0x120)*512;long size=u32(h,0x124)*512;
                if(start<512||size<512||start>f.length()-512||size>f.length()-start)throw new IOException("The cartridge's main partition is missing or truncated.");
                f.seek(start);f.readFully(h);magic=text(h,0x100,4);
            }
            if(!magic.equals("NCCH"))throw new IOException("Choose a decrypted CCI / 3DS cartridge or CXI file. CIA installers, archives and saves are not supported here.");
            long partitionSize=u32(h,0x104)*512;
            if(partitionSize<512||partitionSize>f.length()-start)throw new IOException("The 3DS game partition is truncated.");
            if((h[0x18f]&4)==0)throw new IOException("This cartridge is encrypted. This preview accepts your own decrypted cartridge dump.");
            return text(h,0x150,16);
        }
    }
}
