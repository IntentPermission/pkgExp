package org.andr.pkgexp;

import android.os.*;
import com.ibm.ssm.*;
import com.ibm.ssm.tree.*;
import com.ibm.ssm.tree.nob.*;
import java.io.*;
import java.lang.*;
import java.lang.Process;
import ribo.ssm.SSMcmd;
import static org.andr.pkgexp.PackageExplorer.jsBridge;
import static org.andr.pkgexp.PackageExplorer.prt;

public class PkgExpUtil {
  public static String baseDir = "/sdcard/ssm";
  public static String defObjStore = "FILE " + baseDir + "/store .nob";

    public static String
  execOSCmd(String cmd) {
    StringBuffer sb = new StringBuffer();
    execOSCmd(cmd, sb);
    return sb.toString();
  }
    public static int
  execOSCmd(
    String cmd,
    StringBuffer sb  // Place to return stdout/stderr (or null to ignore)
  ) {
    return execOSCmd(cmd, sb, null, null);
  }

    public static int
  execOSCmd(
    String cmd,
    StringBuffer sb,  // Place to return stdout/stderr (or null to ignore)
    String[] env,
    File cwd
  ) {
    String line;  int rc = -999;
    Process proc;
    try { 
      // Launch command in a separate process
      proc = Runtime.getRuntime().exec(cmd, env, cwd);
      
      rc = proc.waitFor();  // Wait for the process to complete
      //prt("Command '"+cmd+"' returned rc="+rc);
      InputStreamReader isr = new InputStreamReader(proc.getErrorStream());
      BufferedReader rdr = new BufferedReader(isr);
      while((line = rdr.readLine()) != null) { 
        sb.append(line);  sb.append("\n");
      } 
      isr = new InputStreamReader(proc.getInputStream());
      rdr = new BufferedReader(isr);
      while((line = rdr.readLine()) != null) { 
        sb.append(line);  sb.append("\n");
      } 
    } catch(Throwable ex) {
      String exMsg;
      if (ex == null) {  // ?? On Android, ex may null
        exMsg = "ex is null for cmd: "+cmd;
      } else {
        exMsg = ex+"\n"+stackTrace(ex);
        Throwable cause = ex.getCause();
        if (ex != cause) {
          exMsg += "\n\ncause ex: "+cause+"\n"+stackTrace(cause);
        }
      }
      System.err.println("  execOSCmd ex: "+exMsg);
      sb.append("(");  sb.append(exMsg);  sb.append(")");
    } 
    return rc;
  } // end of execOSCmd

  
  public static String stackTrace(
    Throwable t
  ) {
    StringBuffer sb = new StringBuffer();
    StackTraceElement[] ste = t.getStackTrace();
    for (int i=0; i<ste.length; i++) { 
      sb.append(ste[i].toString());  sb.append("\n");
    }
    return sb.toString();
  } // end of stackTrace
    
    
  public static String stackTrace() {
    Exception ex = new Exception();
    ex.fillInStackTrace();
    return stackTrace(ex);
  } // end of stackTrace


// Our handler to catch any exception in this app
private Thread.UncaughtExceptionHandler defaultUEH;
Thread.UncaughtExceptionHandler vpUEH = new Thread.UncaughtExceptionHandler() {
  //@Override
  public void uncaughtException(Thread th, Throwable ex) {
    String stkTr = SSMutil.stackTrace(ex);
    prt("\n!!!!VPapp uncaught exception: thread "+th+", cause: "+ex.getCause()
      +"\n"+SSMutil.stackTrace(ex.getCause())+"\n\n"+ex+"\n"+stkTr);

    if (ex.getCause() instanceof OutOfMemoryError) {
      try {
        prt("--before dumpHprofData. th "+th.getName());
        Debug.dumpHprofData("/sdcard/ssm/vp.heap");
        prt("--after dumpHprofData. th "+th.getName());
      } catch (Exception ex2) {
        prt("VPapp.uncaughtException ex: " + ex2 + "\n" + SSMutil.stackTrace(ex2));
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      PackageExplorer.pkgExp.finishAffinity();  // Close all other activities of this app?
    } else {
      System.exit(0);
    }
    // re-throw critical exception further to the os (important)
    defaultUEH.uncaughtException(th, ex);
  }
};


public static Tree remNB(String oid) {
  String svr = "localhost", svrOid = null;
  int ii = oid.indexOf('/');
  if (ii >= 0) { // No '/' in oid, this must be a local obj
    svrOid = oid.substring(0, ii);
  }
  oid = oid.substring(ii+1);

  Tree tr;
  if (svrOid != null) {
    tr = remNB(svrOid);  // Is this string a NOB?  (Rather than hostname)
    if (tr != null) {
      String host = tr.value("HOST"), port = tr.value("PORT");
      svr = port != null ? host + ":" + port : host;
      tr.release();
    }
    if (svr == null) {
      prt("VPthumbs.remNB, SSM server oid: " + svrOid + " not found, or no HOST node");
    }
  }

  Tree cmdTr = TrunkFactory.newTree();
  cmdTr.changeValue("WRT "+oid+" GETOBJ");
  tr = SSMCom.cmdReply(svr, cmdTr);
  if (tr != null) {
    tr.getTrunk().setOid(oid);  // Set the object name into our in memory copy
  }
  cmdTr.release();
  return tr;
} // end of remNB


static class PkgSSMHndCmd extends SSMcmd.SSMHndCmd {
  public Tree hndCmd(Tree cmdTr) {
    Bytes args = new Bytes();
    cmdTr.valueBytes(args);
    Bytes cmd = new Bytes();
    args.parseTok(cmd);
    String respStr = null;
    Tree respTr = new NB();

    prt("PkgSSMHndCmd.hndCmd: " + cmd + " " + args);
    if (cmd.compareToIgnoreCase("HELLO") == 0) {
      respStr = "Bye from VP";

    } else if (cmd.compareToIgnoreCase("sendCmd") == 0) {
      respStr = PackageExplorer.RunJScmd.cmdReply(args.toString());
      prt("  PkgSSMHndCmd.sendCmd after RunJScmd: '"+args+"'");
      if (respStr.length() > 50000) {
        respStr = writeToSSM("pkgexp.resp", respStr);
      }

    } else {
      // Try processing this using the normal SSM cmd resolution mechanism
      SSMSessCtx sctx = new SSMSessCtx();
      sctx.com = new SSMComTr();  // Cause sendResp to save response in sctx.com.tr
      SSM.procCmds(sctx, cmdTr);
      respTr = ((SSMComTr)sctx.com).tr;
      sctx.release();
    }

    if (respStr != null) {
      respTr.changeValue(respStr);
    }
    return respTr;
  } // end of hndCmd
} // end of PkgSSMHndCmd class


public static String writeToSSM(String fid, Object cont) {
  try {
    Tree cTr = new NB();
    Bytes byteCont = new Bytes();
    byteCont.arr = cont instanceof String ? ((String)cont).getBytes() : (byte[])cont;
    byteCont.end = byteCont.arr.length;
    cTr.changeValue("IAM E11 WRITE "+fid);
    cTr.add(new Bytes("CONTENT"), byteCont);
    Tree rTr = SSMCom.cmdReply("localhost", cTr);
    prt("Write response: "+rTr.list());
    cTr.release(); rTr.release();

    return "(See long response in /sdcard/tmp/"+fid+")";
  } catch (Exception ex) {
    return "PkgExpUtil.PkgSSMHndCmd.sendCmd writeFile ex: "+ex+"\n"+SSMutil.stackTrace(ex);
  }
} // end of writeToSSM


public static void minimalSSMinit() {
  if (SSMutil.objStoreTr != null) return; // Already done, leave

  SSMutil.objStoreTr = TrunkFactory.newTree();
  SSMutil.objStoreTr.add("xx", defObjStore);
  SSM.logServer = null;
  SSM.consoleOS = System.out;

  // For VPSSMHndCmd, setup config context
  Thread initTh = new Thread() { // Run the renNB and saveObj not in main UI thread
    public void run() {
      SSM.configOid = "ANDRCONFIG";
      SSM.configTr = remNB(SSM.configOid);
      if (SSM.configTr==null || SSM.configTr.select("CONFIG")==false) {
        prt("!!PkgExpUtil.minimalSSMinit: Can't find 'CONFIG' node in '"+SSM.configOid+"' config object.");
      }
    }
  };
  initTh.start();
} // end of minimalSSMinit

} // end of class PkgExpUtil
