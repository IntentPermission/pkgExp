package org.andr.pkgexp;

import com.ibm.ssm.*;
import com.ibm.ssm.tree.nob.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import static org.andr.pkgexp.PackageExplorer.isToByte;
import static org.andr.pkgexp.PackageExplorer.prt;
import static org.andr.pkgexp.PackageExplorer.LEW;
import static org.andr.pkgexp.PackageExplorer.LEH;

public class ParseResources {
  byte[] buf;
  int endOfFileOff;
  ResChunkHdr masterRCH;
  ResChunkHdr packageRCH;
  ResStringPool appStr;
  ResStringPool str8;
  ResStringPool str8b;  // ?? A second UTF-8 string pool
  ResStringPool str16;
  ResGroup[] group = new ResGroup[16];  // This just creates the array, doesn't create ResGroup instances
    // groupId is the second byte of a resourceId (ie 0x00FF0000)

  public static int resChkHdrSize = 8;  // Size of the basic chunk header

public ParseResources(InputStream is) {
    int off = 0;
    try {
      buf = isToByte(is);

      // Scan all the resChunks in the file, remember where all the tables are
      masterRCH = new ResChunkHdr(0);
      endOfFileOff = masterRCH.getSize();
        // Master should be type 0x0002, hdrSize 0xc
      off = masterRCH.off + masterRCH.getHdrSize();  // Point to next sibling chunk

      // Read chunks until we find the ResTablePackage chunk (type = 0x0200)
      ResChunkHdr chk = new ResChunkHdr(off);
      while (chk.getType() != 0x0200) {
        if (chk.getType() == 0x0001) {
          appStr = new ResStringPool(off);  // Remember where this string pool is
          prt("  appStr off "+Integer.toHexString(appStr.off)+" - "
            +Integer.toHexString(appStr.getSize())+", UTF-"+(appStr.isUTF8() ? 8 : 16)
            +", strCnt "+Integer.toHexString(appStr.getStrCnt()));
        } else {
          prt("  chunk at "+Integer.toHexString(chk.off)+" - "
            +Integer.toHexString(chk.getSize())+", type "+Integer.toHexString(chk.getType()));
        }

        chk.chunkHdrAt(chk.off + chk.getSize());   // Move forward the size of the full chunk
      }

      // Display resTablePackage chunk/struct (type 0x0200)
      // name  decode("UTF-16") of 128 char name at
      // typeStrings LEW(intOff)
      // lastPublicType = LEW(intOff+4)
      // keyStrings = LEW(intOff+8)
      // lastPublicKey = LEW(intOff+12)
      // typeIdOffset = LEW(intOff+16)

      // Scan through all child chunks of this ResTablePackage chunk
      packageRCH = chk;
      chk = new ResChunkHdr(chk.off+chk.getHdrSize());  // Create RCH to first child chunk
      while (chk.off < endOfFileOff) {
        switch (chk.getType()) {
          case 0x0001:  // ResStringPool type of chunk, UTF-8 or UTF-16 strings
            // Read type 0x0001 ResStringPool chunk, to look up name
            // use UTF-8 string pool subchunk in ResTable_package for this app
            ResStringPool sp = new ResStringPool(chk.off);
            if (sp.isUTF8()){
              if (str8 == null) {
                str8 = sp;
              } else {
                str8b = sp;
              }
              prt("  str8 off "+Integer.toHexString(str8.off)+" - "+Integer.toHexString(str8.getSize()));
            } else {
              str16 = sp;
              prt("  str16 off "+Integer.toHexString(str16.off)+" - "+Integer.toHexString(str16.getSize()));
            }
            prt("  ResStringPool chunk at "+Integer.toHexString(chk.off)+", UTF-"
              +(sp.isUTF8() ? 8 : 16)+", strCnt "+Integer.toHexString(sp.getStrCnt())
              +", styleCnt "+Integer.toHexString(sp.getStyleCnt()));
            break;

          case 0x0202:  // ResTable_typeSpec stuct/chunk, one for each resId 'groupId'
            ResTypeSpec rts = new ResTypeSpec(chk.off);
            int groupId = rts.getGroupId();
            if (groupId<group.length) {
              if (group[groupId] == null) group[groupId] = new ResGroup();
              group[groupId].rts = rts;
            } else {
              prt("ParseResources.ResTable_typeSpec: GroupId "+Integer.toHexString(groupId)
                +" too big for chunk at "+Integer.toHexString(chk.off));
            }
            prt("  ResTable_typeSpec chunk at "+Integer.toHexString(chk.off)+", groupId "
              +Integer.toHexString(groupId)+", entryCnt "+Integer.toHexString(rts.getEntryCnt()));
            break;

          case 0x0201:  // ResTable_type stuct/chunk ( Multiple type chunks per resId 'groupId')
            ResType rt = new ResType(chk.off);
            groupId = rt.getGroupId();
            if (groupId<group.length) {
              if (group[groupId] == null) group[groupId] = new ResGroup();
              ResGroup rg = group[groupId];
              if (rg.rt == null) {  // Save only the first, default Type, ignore the config variations
                rg.rt = rt;
              }
              rg.resTypeCnt++;  // Although we ignore the other type structs, keep a count
            } else {
              prt("ParseResources.ResTable_type: GroupId "+Integer.toHexString(groupId)
                +" too big for chunk at "+Integer.toHexString(chk.off));
            }
            prt("  ResTable_type chunk at "+Integer.toHexString(chk.off)
              +", groupId "+Integer.toHexString(rt.getGroupId())
              +", entryCnt "+Integer.toHexString(rt.getEntryCnt()));
            break;

          default:
            prt("  unwanted "+Integer.toHexString(chk.getType())+" chunk  at "+Integer.toHexString(chk.off));
        } // end of switch for handling different chunk types of children of package
        chk.chunkHdrAt(chk.off+chk.getSize());  // Step to start of next sibling chunk Hdr
      } // end of while loop scanning chunks that are children of the ResTablePackage
    } catch (Exception ex) {
      prt("ParseResources ex: "+ex+"\n"+ SSMutil.stackTrace(ex));
    }
  } // end of ParseResources InputStream constructor


  public String getResource(int resId) {  // Return the value associated with this resource
    ResGroup rg = group[resId>>16&0xff];
    ResType rt = rg.rt;
    int ent[] = rt.getEnt(resId&0xffff);
    int nameInd = ent[1], valInd = ent[3];
    String nameStr = str8.getStr(nameInd);
    String valStr = str8.getStr(valInd);
    return valStr;
  } // end of getResource


public class ResChunkHdr {
    int off;

    public ResChunkHdr(int off) {  // Read and parse the Resource Chunk Header at off
      this.off = off;
    }

    public ResChunkHdr chunkHdrAt(int off) {
      this.off = off;  // Relocate where this object points to
      return this;
    }
    public int getType() { return LEH(buf, off); }
    public int getHdrSize() { return LEH(buf, off+2); }
    public int getSize() { return LEW(buf, off+4); }

    public String toString() {
      StringBuffer sb = new StringBuffer();
      sb.append("Chunk at "+Integer.toHexString(off)+", type "+Integer.toHexString(getType())
        +", hdrSize "+Integer.toHexString(getHdrSize())+", size "+Integer.toHexString(getSize())
        +"\n");
      Ribo.hexdump(buf, off&0xfffffff0, getHdrSize()+(off&0x0000000f), sb);
      return sb.toString();
    }
  } // end of ResChunkHdr


public class ResStringPool extends ResChunkHdr {
  public ResStringPool(int off) {
    super(off);
  }

  public boolean isUTF8() { return (getFlags()&0x0100) == 0x0100; }
  public int getStrCnt() { return LEW(buf, off+resChkHdrSize+0); }
  public int getStyleCnt() { return LEW(buf, off+resChkHdrSize+4); }
  public int getFlags() { return LEW(buf, off+resChkHdrSize+8); }
  public int getStrStart() { return LEW(buf, off+resChkHdrSize+12); }
  public int getStyleStart() { return LEW(buf, off+resChkHdrSize+16); }

  public String getStr(int strNo) {
    if (strNo >= getStrCnt()) return null;
    int ind = LEW(buf, off+getHdrSize()+4*strNo);  // Index from strNo work in array
    int strOff = off+getStrStart()+ind;
    int cnt = LEW(buf, strOff);
    int charLen, byteLen, cntLen;
    if (isUTF8()) {  // For UTF-8, expect two 1 or 2 byte lengths
      charLen = buf[strOff];
      cntLen = 1;
      if ((charLen&0x80)==0x80) { // Is this a 2 byte length
        charLen = (charLen&0x7f)<<8 + buf[strOff+cntLen];
        cntLen++;
      }
      byteLen = buf[strOff+cntLen];
      cntLen++;
      if ((byteLen&0x80)==0x80) { // Is this a 2 byte length
        byteLen = (byteLen&0x7f)<<8 + buf[strOff+cntLen];
        cntLen++;
      }
    } else {
      cntLen = 2;
      charLen = buf[strOff] | buf[strOff+1]<<8;
      byteLen = 2*charLen;
    }
    String str = new String(buf, strOff+cntLen, byteLen);
    return str;
  } // end of getStr


  public String[] getStrs() {
    String[] strs = new String[getStrCnt()];
    for (int ii=0; ii< strs.length; ii++) {
      strs[ii] = getStr(ii);
    }
    return strs;
  } // end of getStrs
} // end of ResStringPool


public class ResTypeSpec extends ResChunkHdr {
  public ResTypeSpec(int off) {
    super(off);
  }

  public int getGroupId() { return buf[off+resChkHdrSize]; }
  public int getEntryCnt() { return LEW(buf, off+resChkHdrSize+4); }
  public int getEntryStart() { return LEW(buf, off+resChkHdrSize+8); }
  public int getVal(int entNo) {
    if (entNo >= getEntryCnt()) return -1;
    return LEW(buf, off+getHdrSize()+4*entNo);

  }
} // end of ResTypeSpec


public class ResType extends ResTypeSpec {
  public int resTypeEntLen = 16;
  public ResType(int off) {
    super(off);
  }

  public int[] getEnt(int entNo) {
    if (entNo >= getEntryCnt()) return null;
    int entOff = off+getEntryStart()+resTypeEntLen*entNo;
    int ent[] = {LEW(buf, entOff), LEW(buf, entOff+4), LEW(buf, entOff+8), LEW(buf, entOff+12)};
    return ent;
  }
} // end of ResType class


public static class ResGroup {  // For each groupId, there is a ResTypeSpec and one or more ResTypes
  public ResTypeSpec rts;
  public ResType rt;
  public int resTypeCnt;  // Number of ResTypes seen (default and one per 'config)
} // end of ResGroup


public static void main(String[] args) throws IOException {
  String fid = args[0];
  ParseResources pRes;

  try {
    JarFile jf = new JarFile(fid);
    JarEntry je = (JarEntry)jf.getEntry("resources.arsc");
    pRes = new ParseResources(jf.getInputStream(je));

    prt("pRes "+pRes.getResource(0x7f080001));
  } catch (Exception ex) {
    prt("ParseResouces ex: "+SSMutil.stackTrace(ex));
  }
}


} // end of ParseResources class
