// PackageExplorer -- R A Flavin
// Display information about Android Packages installed on a machine
//
// (c) Copyright IBM Corp, R A Flavin, 2011

package org.andr.pkgexp;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

import com.ibm.ssm.*;
import com.ibm.ssm.tree.Bytes;
import com.ibm.ssm.tree.Tree;
import com.ibm.ssm.tree.TrunkFactory;

import android.app.Activity;
import android.content.Context; 
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.MenuItem.OnMenuItemClickListener;
import android.webkit.*;
import android.widget.LinearLayout;
import ribo.ssm.*;
import static org.andr.pkgexp.PkgExpUtil.execOSCmd;
import static org.andr.pkgexp.PkgExpUtil.writeToSSM;
import static ribo.ssm.SSMcmd.ssmCmd;

public class PackageExplorer extends Activity implements DownloadListener,
  OnMenuItemClickListener {
  public static boolean showIntents = true;
  public static boolean showManifestXML = false;
  public static boolean showPackageList = false;
  public static boolean showPackageDetails = true;
  public static boolean showArrays = true;
  public static boolean showApplicationList = false;
  public static boolean showApplicationDetails = false;
  
  public static PackageExplorer pkgExp;
  public LinearLayout pkgLay; 
  public static WebView brView;
  public static Thread mainThread;
  public static BgThread bgThread;
  public Context ctx;
  public Display disp;
  public static SharedPreferences prefs;
  public static String pkgName;  // Name of package currently being decompressed
  public static PkgExpUtil.PkgSSMHndCmd pkgSHC;
  public static JsBridge jsBridge;
  public static int pkgIndex;
  Vector<String> pkgVect = new Vector<String>();
  public static ParseResources pRes;  // static for debugs by evalPkg command

// Place to accumulate lists of things for the whole scan
  public static Tree intentTr = TrunkFactory.newTree();
  public static Tree activityTr = TrunkFactory.newTree();
  public static Tree permissionTr = TrunkFactory.newTree();
  public static int totIntents;  // Total number of intent-filters found
  public static int totActivities;  // Total number of activities found
  public static int totPermissions;  // Total number of uses-permissions found

  // Directories to search for .apk files
  String[] paths = {"/system/app", "/data/app",  // Common locations
    "/product/app", "/product/priv-app", "/product/overlay"  // Found on Google Pixel
  };

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    pkgExp = this;
    if (ctx != null) {
      prt("Duplicate onCreate");
    }

    prefs = getSharedPreferences("pkg", 0);
    String str = prefs.getString("startSSM", "false");
    if (str!=null && str.compareToIgnoreCase("true")==0) {
      //startSSM();  // START DEBUG CONSOLE
    }
        
    brView = new WebView(this);
    brView.setId(R.id.brView);  // Needed for saving state????
    WebSettings wvSet = brView.getSettings();
    wvSet.setJavaScriptEnabled(true);
    //wvSet.setNavDump(true);  // ??
    brView.setWebChromeClient(new PkgWebChromeClient());
    brView.setWebViewClient(new PkgWebViewClient());
    brView.setContentDescription("brView");
    brView.setDownloadListener(this);
    //wv.setPictureListener(this);
    //WebView.enablePlatformNotifications();
    jsBridge = new JsBridge();  // Communication between us and JS
    brView.addJavascriptInterface(jsBridge, "JavaBase");
    
    ctx = getApplicationContext();
    pkgLay = new LinearLayout(ctx);
    disp = this.getWindowManager().getDefaultDisplay();
    //pkgLay.addView(brView, disp.getWidth(), disp.getHeight());
    pkgLay.addView(brView);
    
    Window wind = getWindow();
    wind.setContentView(pkgLay);
    
    // Load raw page from a resource
    byte[] basePg = null;
    int br, ind=0;
    try {
      InputStream is = this.getResources().getAssets().open("base.html");
      // Note: Android 2.1 InputStream from getResources always just returns
      // 1 from available()  (rather than full file length as 2.2 does)
      basePg = new byte[10240]; 
      while ((br = is.read(basePg, ind, basePg.length-ind)) >= 0) {
        ind += br;
        if (ind >= basePg.length) { // Buffer full, get a larger buffer
          byte[] newBuf = new byte[basePg.length+10240];
          System.arraycopy(basePg, 0, newBuf, 0, basePg.length);
          basePg = newBuf;
        }
      }
    } catch (Exception ex) {
      prt("onCreate ex: "+ex+"\n"+PkgExpUtil.stackTrace(ex));
    }
    //brView.loadData(new String(basePg), "text/html", "utf-8"); 
    // Javascript doesn't seem to work with the above.
    brView.loadDataWithBaseURL("http://www.ibm.com", new String(basePg, 0, ind),
      "text/html", "utf-8", null);
    
    mainThread = Thread.currentThread();  // Remember which is the main thread.
    bgThread = new BgThread();  // A place to do the main (slow) processing
    bgThread.setName("bgThread");
    // brThread is started by onPageFinished (delay any calls to RunJsCmd
    // until after the WebKit system has the page up and ready to accept 
    // updates.

    // Share our Context with SSMcmd
    SSMcmd.ctx = getApplicationContext();
    pkgSHC = new PkgExpUtil.PkgSSMHndCmd();
    SSMcmd.cmdHndlr = pkgSHC;
    prt("PkgExp.onCreate calls ssmCmd(null, Hello to ssm)");
    ssmCmd(null, "hello from "+this.getClass().getName());

  } // end of onCreate


  // NO LONGER USED, the main menu is now done as a menu button on each page
  public boolean onPrepareOptionsMenu(Menu menu) {
    try {
      prt("On prepareOptMenu "+menu.size());
      if (menu.size() < 3) {
        // Main menu
        //SubMenu smScapes = menu.addSubMenu(Menu.NONE, 0, Menu.NONE, "Scapes");
        MenuItem mi = menu.add(Menu.NONE, 1, Menu.NONE, "Packages");
        mi = menu.add(Menu.NONE, 1, Menu.NONE, "Intent-filters");
        mi = menu.add(Menu.NONE, 1, Menu.NONE, "Activities");
        mi = menu.add(Menu.NONE, 1, Menu.NONE, "Uses-permissions");
        mi = menu.add(Menu.NONE, 1, Menu.NONE, "Help");
        //SubMenu sm = menu.addSubMenu(Menu.NONE, 0, Menu.NONE, "...");

        // Scapes submenu
        //mi = smScapes.add(Menu.NONE, 20, Menu.NONE, "Home");
        //mi.setOnMenuItemClickListener(this);
        //mi = smScapes.add(Menu.NONE, 21, Menu.NONE, "Media");
        //mi.setOnMenuItemClickListener(this);
              }
    } catch (Exception ex) {
      prt("  onPrepareOptionsMenu ex: "+ex+"\n");
    }
    return true;
  } // end of onPrepareOptionsMenu

  
  public boolean onMenuItemClick(MenuItem item) {
    Log.w("ctxMenuClick", "  click "+item);
    return false;
  }
  
  
  public void onBackPressed() {
    RunJScmd.cmd("showPage()");  // No arg to showPage means 'back'
  } // end of onBackPressed
  

  // NO LONGER USED, now done by menu button in HTML on each page
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle item selection
    String title = (String)item.getTitle();
    prt("Option "+title+" selected");
    breakBlock: {
      String key=null, value=null;
      if (title.compareToIgnoreCase("Packages") == 0) {
        RunJScmd.cmd("showPage('packages')");
        prt("menuclck\n"+showViews(pkgLay));
      } else if (title.compareToIgnoreCase("Intent-filters") == 0) {
        RunJScmd.cmd("showPage('intents')");
      } else if (title.compareToIgnoreCase("Activities") == 0) {
        RunJScmd.cmd("showPage('activities')");
      } else if (title.compareToIgnoreCase("Uses-permissions") == 0) {
        RunJScmd.cmd("showPage('permissions')");
      } else if (title.compareToIgnoreCase("Help") == 0) {
        RunJScmd.cmd("showPage('help')");
      } else {
        // Clicks on submenus usually have no processing here, just leave
        break breakBlock;
      }

      // Save the updates value in the preferences
      if (key != null) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(key, value);  ed.apply();
      }
    }
    return true;  // true, processed event, prevent default behavior
  } // end of onOptionsItemSelected

  
  public void onConfigurationChanged(Configuration  newConfig) {
    super.onConfigurationChanged(newConfig);
    prt("onConfigurationChanged: "+newConfig);
    prt("pkgLay before: "+showViews(pkgLay));
    View wv = pkgLay.getChildAt(0);
    int ww = disp.getWidth(), hh = disp.getHeight();
    // Note: System has already changed getWidth/Hieght to new orientation
        
    if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
      prt("  PORTRAIT "+ww+"x"+hh);
      brView.layout(0, 0, ww, hh);
    } else {
      prt("  LANDSCAPE "+ww+"x"+hh);
      brView.layout(0, 0, ww, hh);
    }
    prt("pkgLay after: "+showViews(pkgLay));
  } // end of onConfigurationChanged
  
  
public class BgThread extends Thread {
  public ArrayBlockingQueue queue = new ArrayBlockingQueue(10);

  public void run() {
    int ii, jj;

    PkgExpUtil.minimalSSMinit();  // For debugging, start the SSM listener

    if (showIntents) {
      // On pixel: /product/app, /product/priv-app, /product/overlay
      //path = "/data/app"; -- not public Read on unrooted system
      Tree resTr = null;
      // First pass, displaying the package names, (no twisty plus sign yet)
      for (ii=0; ii<paths.length; ii++) {
        String htm = "<div>packages in: <span style=\"color: blue; height: 40px;\">"
          +paths[ii]+"</span></div>";
        RunJScmd.cmd("append('pkgList', '"+htm+"')");
        File ff = new File(paths[ii]);
        String[] fl = ff.list();
        if (fl == null) {  // If that failed, try looking as root
          fl = apkListRoot(paths[ii]);
        }

        if (fl == null) {
          htm = "(no files visible in \"" + paths[ii] + "\", got root?)";
          htm = "<div style=\"padding-left: 20px; color: red; height: 40px;\">" + htm + "</div>";
          RunJScmd.cmd("append('pkgList', '" + htm + "')");

        } else {
          String pkgName;
          for (jj=0; jj<fl.length; jj++) {
            String pkg = fl[jj];
            if (pkg.lastIndexOf(".apk") == -1) {
              // On Samsung Tab, this may be a directory containing the .apk
            	// (and other files), look for the pkg inside the directory
              File pf = new File(paths[ii]+'/'+pkg);
              if (!pf.isDirectory()) continue;  // Skip non .apk, non directories
              pf = new File(paths[ii]+'/'+pkg+'/'+pkg+".apk");
              if (!pf.isFile()) continue;
              pkgName = pkg;
              pkgVect.add(paths[ii]+"/"+pkg+'/'+pkg+".apk");

            } else {
              pkgName = findPkgName(pkg);
              pkgVect.add(paths[ii]+"/"+pkg);
              // Strip the package name from the directories names and other stuff
              // pkg may be: ~~NC7987897986==/com.google.android.gms-776sdf67sd6f7/base.apk
            }
            htm = "{id: '"+pkgName+"', title: '"+pkgName+"'}";
            RunJScmd.cmd("insertFrag('pkgList', fragType.twisty.template, "+htm+")");
          }
        }
      } // end of for loop scanning all directories in the paths list
      
      // Second pass, parse each manifest file, change element to a twisty
      for (ii=0; ii<pkgVect.size(); ii++) {
        pkgIndex = ii;  // Temp hack, pass index counter to parseResaurses for writeToSSM
        String pkgPath = pkgVect.get(ii);
        pkgName = findPkgName(pkgPath);

        buildManifest(pkgPath, false);  // First pass, parseResources=false, for speed
        RunJScmd.cmd("setPkgCnt("+(ii + 1) + ", " + pkgVect.size() + ")");
      } // end of for loop parsing manifests for all packages
    } // end of showIntents block
    
    PackageManager pm = ctx.getPackageManager();
    if (showPackageList) {
      // GET THE LIST OF ALL PACKAGES
      int flags = PackageManager.GET_ACTIVITIES 
      | PackageManager.GET_GIDS
      | PackageManager.GET_CONFIGURATIONS
      | PackageManager.GET_INSTRUMENTATION
      | PackageManager.GET_PERMISSIONS
      | PackageManager.GET_PROVIDERS
      | PackageManager.GET_RECEIVERS
      //| PackageManager.GET_SERVICES  //-- Mutually exclusive with GET_ACTIVITIES?
      //| PackageManager.GET_SIGNATURES  // Also, can't have with GET_ACTIVITIES?
      ;
      // GET_UNINSTALLED_PACKAGES  -- includes packages deleted with DONT_DELETE_DATA
      List<PackageInfo> pkgs = pm.getInstalledPackages(flags);
      prt("\nPACKAGES total "+pkgs.size());
      for (ii=0; ii<pkgs.size(); ii++) {
        PackageInfo pInfo = pkgs.get(ii);
        if (showPackageDetails) {
          String details = "  packageName: "+pInfo.packageName+"\n"
          +"  sharedUserId: "+pInfo.sharedUserId+"\n"
          +"  sharedUserLabel: "+pInfo.sharedUserLabel+"\n"
          +"  versionCode: "+pInfo.versionCode+"\n"
          +"  versionName: "+pInfo.versionName+"\n"
          //+"  describeContents: "+pInfo.describeContents()+"\n"
          +"  activities: "+showArray(pInfo.activities)+"\n"
          +"  applicationInfo: "+pInfo.applicationInfo+"\n"
          +"  configPreferences: "+pInfo.configPreferences+"\n"
          +"  gids: "+pInfo.gids+"\n"
          +"  instrumentation: "+pInfo.instrumentation+"\n"
          +"  permissions: "+showArray(pInfo.permissions)+"\n"
          +"  providers: "+showArray(pInfo.providers)+"\n"
          +"  receivers: "+showArray(pInfo.receivers)+"\n"
          //+"  reqFeatures: "+pInfo.reqFeatures+"\n"
          +"  requestedPermissions: "+showArray(pInfo.requestedPermissions)+"\n"
          +"  services: "+pInfo.services+"\n"
          +"  signatures: "+pInfo.signatures;
          prt("s"+ii+" "+pInfo+"\n"+details);
        } else {
          prt("s"+ii+" "+pInfo);
        }
      } // end of for loop scanning package list
      prt("("+totActivities+" activities)");
    } // end of showing package list

    if (false && showPackageList) {
      // GET THE LIST OF ALL PACKAGES
      int flags = //PackageManager.GET_GIDS
      //| PackageManager.GET_CONFIGURATIONS
      //| PackageManager.GET_INSTRUMENTATION
      //| PackageManager.GET_PERMISSIONS
      //| PackageManager.GET_PROVIDERS
      //| PackageManager.GET_RECEIVERS
       PackageManager.GET_SERVICES  //-- Mutually exclusive with GET_ACTIVITIES?
      //| PackageManager.GET_SIGNATURES  // Also, can't have with GET_ACTIVITIES?
      ;
      // GET_UNINSTALLED_PACKAGES  -- includes packages deleted with DONT_DELETE_DATA
      List<PackageInfo> pkgs = pm.getInstalledPackages(flags);
      prt("\nSERVICES total "+pkgs.size());
      for (ii=0; ii<pkgs.size(); ii++) {
        PackageInfo pInfo = pkgs.get(ii);
        if (showPackageDetails) {
          String details = "  packageName: "+pInfo.packageName+"\n"
          +"  sharedUserId: "+pInfo.sharedUserId+"\n"
          +"  sharedUserLabel: "+pInfo.sharedUserLabel+"\n"
          +"  versionCode: "+pInfo.versionCode+"\n"
          +"  versionName: "+pInfo.versionName+"\n"
          //+"  describeContents: "+pInfo.describeContents()+"\n"
          +"  activities: "+showArray(pInfo.activities)+"\n"
          +"  applicationInfo: "+pInfo.applicationInfo+"\n"
          +"  configPreferences: "+pInfo.configPreferences+"\n"
          +"  gids: "+pInfo.gids+"\n"
          +"  instrumentation: "+pInfo.instrumentation+"\n"
          +"  permissions: "+showArray(pInfo.permissions)+"\n"
          +"  providers: "+showArray(pInfo.providers)+"\n"
          +"  receivers: "+showArray(pInfo.receivers)+"\n"
          +"  reqFeatures: "+pInfo.reqFeatures+"\n"
          +"  requestedPermissions: "+showArray(pInfo.requestedPermissions)+"\n"
          +"  services: "+pInfo.services+"\n"
          +"  signatures: "+pInfo.signatures;
          prt("p"+ii+" "+pInfo+"\n"+details);
        } else {
          prt("p"+ii+" "+pInfo);
        }
      } // end of for loop scanning package list
      prt("("+totActivities+" activities)");
    } // end of showing package list

    if (showApplicationList) {
      // GET THE LIST OF ALL APPLICATIONS
      int flags = PackageManager.GET_META_DATA | PackageManager.GET_SHARED_LIBRARY_FILES;
      List<ApplicationInfo> apps = pm.getInstalledApplications(flags);
      prt("\nAPPLICATIONS total "+apps.size());
      for (ii=0; ii<apps.size(); ii++) {
        ApplicationInfo aInfo = apps.get(ii);
        if (showApplicationDetails) {
          String details = ""//"  backupAgentName: "+aInfo.backupAgentName+"\n"
          +"  classsName: "+aInfo.className+"\n"
          +"  dataDir: "+aInfo.dataDir+"\n"      
          +"  descriptionRes: "+aInfo.descriptionRes+"\n"      
          +"  describeContents: "+aInfo.describeContents()+"\n"      
          +"  enabled: "+aInfo.enabled+"\n"      
          +"  flags: "+aInfo.flags+" = x"+Integer.toHexString(aInfo.flags)+"\n"      
          +"  icon: "+aInfo.icon+" = x"+Integer.toHexString(aInfo.icon)+"\n"      
          +"  labelRes: "+aInfo.labelRes+" = x"+Integer.toHexString(aInfo.labelRes)+"\n"      
          +"  manageSpaceActivityName: "+aInfo.manageSpaceActivityName+"\n"      
          +"  metaData: "+aInfo.metaData+"\n"      
          +"  name: "+aInfo.name+"\n"      
          +"  nonLocalizedLabel: "+aInfo.nonLocalizedLabel+"\n"      
          +"  packageName: "+aInfo.packageName+"\n"      
          +"  permission: "+aInfo.permission+"\n"      
          +"  processName: "+aInfo.processName+"\n"      
          +"  publicSourceDir: "+aInfo.publicSourceDir+"\n"      
          +"  sharedLibraryFiles: "+aInfo.sharedLibraryFiles+"\n"      
          +"  sourceDir: "+aInfo.sourceDir+"\n"      
          +"  targetSdkVersion: "+aInfo.targetSdkVersion+"\n"      
          +"  taskAffinity: "+aInfo.taskAffinity+"\n"      
          +"  uid: "+aInfo.uid+"\n"      
          +"  loadDescription: "+aInfo.loadDescription(pm)+"\n"      
          +"  loadIcon: "+aInfo.loadIcon(pm)+"\n"      
          +"  loadLabel: "+aInfo.loadLabel(pm)+"\n"      
          +"  loadXmlMetaData manifest: "+aInfo.loadXmlMetaData(pm, "manifest")+"\n"
          +"  loadXmlMetaData app: "+aInfo.loadXmlMetaData(pm, "application")+"\n"
          +"  loadXmlMetaData act: "+aInfo.loadXmlMetaData(pm, "activity");     
          prt("a"+ii+" "+aInfo.loadLabel(pm)+"\n"+details);
        } else {
          prt("a"+ii+" "+aInfo.loadLabel(pm));
        }
      } // end of for loop scanning package list
    } // end of showing application list

    // Loop waiting for background work items to be put on our queue
    while (true) {
      try {
        Object task = queue.take();
        prt("BgThread.queue got: "+task);
        if (task instanceof Runnable) {
          ((Runnable)task).run();

        } else {  // Assume it is a string: functionName arg1 otherArgs...
          Bytes line = new Bytes((String)task),  tok = new Bytes();
          line.parseTok(tok);  // Get the functionName to perform
          if (tok.compareTo("buildManifest") == 0) {
            String pkgName = line.parseTok(tok).toString();
            buildManifest(pkgName, true);
          } else {
            prt("BgThread.queue: '"+tok+"' is not a known background operation.");
          }
        }

      } catch (InterruptedException iEx) {
        prt("BgThread.run.queue ex: "+ SSMutil.stackTrace(iEx));
        break;
      }
    }
  } // end of BgThread.run


  public void buildManifest(String pkgPath, boolean parseResources) {
    //ParseResources pRes = null;
    Tree manTr = null;
    try {  // Process the important files in the .apk (manifext and resources)
      JarFile jf = new JarFile(pkgPath);
      Enumeration jarEnum = jf.entries();
      while (jarEnum.hasMoreElements()) {
        JarEntry je = (JarEntry) jarEnum.nextElement();
        String jeName = je.getName();

        //prt("  -- " + jeName + " " + je.getSize());
        if (jeName.compareTo("AndroidManifest.xml") == 0) {
          manTr = parseManifest(pkgPath, jf.getInputStream(je), pRes);
        } else if (jeName.compareTo("resources.arsc")==0 &&  parseResources) {
          pRes = new ParseResources(jf.getInputStream(je));
        }
      } // end of while loop inspecting all the files of the jar file

      prt("buildManifest "+ pkgName+(parseResources ? ", parseRes" : ""));
      StringBuffer sb = new StringBuffer();
      manTr.list(0, 0, 999, sb);  // Get tree in a string buffer
      tidyTree(sb);
      RunJScmd.cmd("insertTwistyBody('" + pkgName + "', \"" + sb +"\", "+parseResources
        +", '"+pkgPath+"')");

      manTr.release();
    } catch (Exception ex) {
      prt("BgThread.parseApk, ex: "+ex+"\n"+PkgExpUtil.stackTrace(ex));
    }
  }


  /** Cleanup the normal output of Tree.list() for use in HTML.  Make the
   * indenting not collapse (ie change occurances of spaces to &nbsp;) and
   * change '\n' characters to "\\n" (This may be needed because of bug in
   * passing quoted strings containing LFs to WebKit for use with eval())
   * 
   * @param sb
   */
  public void tidyTree(StringBuffer sb) {
    int ii, cc;
    for (ii=0; ii<sb.length(); ii++) {
      if ((cc = sb.charAt(ii)) == ' ') {
        sb.replace(ii, ii+1, "&nbsp;&nbsp;");
        ii += 12-1;  // Skip over the &nbsp;&nbsp;
      } else if (cc == '\n') {
        sb.replace(ii, ii+1, "\\n");
        ii++;
      }
    }
  } // end of tidyTree
  
} // end of class BgThread


public static String[] apkListRoot(String path) {
  StringBuffer sb = new StringBuffer();
  String[] strArr = null;
  String cmd = "su -c find "+path+" -name *.apk";
  int rc = execOSCmd(cmd, sb);
  if (rc != 0) {
    prt("apkListRoot failed to: " + cmd);

  } else {
    // Count the number of newlines, to make the right size array
    int ii = 0, cnt = 0, jj = 0, kk = 0;
    int pathSize = path.length();
    while (true) {
      if ((ii = sb.indexOf("\n", ii)) == -1) break;
      cnt++;
      ii++;
    }
    strArr = new String[cnt];
    for (ii = 0; ii < cnt; ii++) {
      jj = sb.indexOf("\n", kk);
      strArr[ii] = sb.substring(kk+pathSize+1, jj);  // (remove path and '/')
      kk = jj + 1;
    }
  }
  return strArr;
} // end of apkListRoot


public static String findPkgName(String pkg) {
  // Split path/fn into: basePath, lastDir, and fn
  String pkgName, basePath, lastDir, fn;
  int ii, jj = 0;
  if ((jj = pkg.lastIndexOf(".apk")) != -1) {
    pkg = pkg.substring(0, jj);
  }
  pkgName = pkg;
  if ((ii = pkg.lastIndexOf('/')) != -1) {
    pkgName = fn = pkg.substring(ii+1);
    if ((jj = pkg.lastIndexOf('/', ii-1)) != -1) {
      lastDir = pkg.substring(jj+1, ii);
      basePath = pkg.substring(0, jj);
      //pkgName = lastDir;
      jj = lastDir.indexOf('-');  // Note the mystery tail str may containa '-'
      if (jj!=-1 && lastDir.length()-jj > 15) {  // If lastDir has a '-' with a long string after...
        pkgName = lastDir.substring(0, jj);  // Remove this seemingly encrypted appendage
        if (fn.compareTo("base") != 0) {
          pkgName = pkgName+" "+fn;  // Add the subpackage name??
        }
      }
    }
  }
  return pkgName;
} // end of findPkgName


public static byte[] isToByte(InputStream is) throws IOException {
  byte[] res = new byte[10240];
  try {
    // Note: Android 2.1 InputStream from getResources always just returns
    // 1 from available()  (rather than full file length as 2.2 does)
    int br, ind = 0;
    while ((br = is.read(res, ind, res.length - ind)) >= 0) {
      ind += br;
      if (ind >= res.length) { // Buffer full, get a larger buffer
        byte[] newBuf = new byte[res.length + 10240];
        System.arraycopy(res, 0, newBuf, 0, res.length);
        res = newBuf;
      }
    }
  } catch (Exception ex) {
    prt("isToByte ex: "+ex+"\n"+PkgExpUtil.stackTrace(ex));
  }
  return res;
} // end of isToByte


public Tree parseManifest(String path, InputStream is, ParseResources pRes) {
  Tree tr = TrunkFactory.newTree();
  try {
    byte[] xml = isToByte(is);

    StringBuffer sb = showManifestXML ? new StringBuffer() : null;
    decompressXML(xml, tr, pRes, sb);
    tr.root();
    if (showManifestXML) prt(sb.toString());
  } catch (Exception ex) {
    prt("parseManifest ex: "+ex+"\n"+PkgExpUtil.stackTrace(ex));
  }
  return tr;
} // end of parseManifest


  // decompressXML -- Parse the 'compressed' binary form of Android XML docs, 
  // such as for AndroidManifest.xml in .apk files
  public static int endDocTag = 0x00100101;
  public static int startTag =  0x00100102;
  public static int endTag =    0x00100103;
  public static String groupNodeNames = "uses-permission";
  public static String useNameAttrAsValue = "action category";
  
  public void decompressXML(
    byte[] xml,      // Binary XML file data, input to this function
    Tree tr,         // Tree in which to build the parsed content
    ParseResources pRes,  // Decoded version of resources.arsc file for getResource
    StringBuffer sb  // Buffer to return XML rendering, optional, may be null
  ) {
    try {
      // Compressed XML file/bytes starts with 24x bytes of data,
      // 9 32 bit words in little endian order (LSB first):
      //   0th word is 03 00 08 00
      //   3rd word SEEMS TO BE:  Offset at then of StringTable
      //   4th word is: Number of strings in string table
      // WARNING: Sometime I indiscriminently display or refer to word in 
      //   little endian storage format, or in integer format (ie MSB first).
      int numbStrings = LEW(xml, 4*4);

      // StringIndexTable starts at offset 24x, an array of 32 bit LE offsets
      // of the length/string data in the StringTable.
      int sitOff = 0x24;  // Offset of start of StringIndexTable

      // StringTable, each string is represented with a 16 bit little endian 
      // character count, followed by that number of 16 bit (LE) (Unicode) chars.
      int stOff = sitOff + numbStrings*4;  // StringTable follows StrIndexTable

      // XMLTags, The XML tag tree starts after some unknown content after the
      // StringTable.  There is some unknown data after the StringTable, scan
      // forward from this point to the flag for the start of an XML start tag.
      int xmlTagOff = LEW(xml, 3*4);  // Start from the offset in the 3rd word.
      // Scan forward until we find the bytes: 0x02011000(x00100102 in normal int)
      for (int ii=xmlTagOff; ii<xml.length-4; ii+=4) {
        if (LEW(xml, ii) == startTag) { 
          xmlTagOff = ii;  break;
        }
      } // end of hack, scanning for start of first start tag

      // XML tags and attributes:
      // Every XML start and end tag consists of 6 32 bit words:
      //   0th word: 02011000 for startTag and 03011000 for endTag 
      //   1st word: a flag?, like 38000000
      //   2nd word: Line of where this tag appeared in the original source file
      //   3rd word: FFFFFFFF ??
      //   4th word: StringIndex of NameSpace name, or FFFFFFFF for default NS
      //   5th word: StringIndex of Element Name
      //   (Note: 01011000 in 0th word means end of XML document, endDocTag)

      // Start tags (not end tags) contain 3 more words:
      //   6th word: 14001400 meaning?? 
      //   7th word: Number of Attributes that follow this tag(follow word 8th)
      //   8th word: 00000000 meaning??

      // Attributes consist of 5 words: 
      //   0th word: StringIndex of Attribute Name's Namespace, or FFFFFFFF
      //   1st word: StringIndex of Attribute Name
      //   2nd word: StringIndex of Attribute Value, or FFFFFFF if ResourceId used
      //   3rd word: Flags?
      //   4th word: str ind of attr value again, or ResourceId of value

      // TMP, dump string table to tr for debugging
      //tr.addSelect("strings", null);
      //for (int ii=0; ii<numbStrings; ii++) {
      //  // Length of string starts at StringTable plus offset in StrIndTable
      //  String str = compXmlString(xml, sitOff, stOff, ii);
      //  tr.add(String.valueOf(ii), str);
      //}
      //tr.parent();

      // Step through the XML tree element tags and attributes
      int off = xmlTagOff;
      int indent = 0;
      int startTagLineNo = -2;
      String attrName="", attrValue="";
      while (off < xml.length) {
        int tag0 = LEW(xml, off);
        //int tag1 = LEW(xml, off+1*4);
        int lineNo = LEW(xml, off+2*4);
        //int tag3 = LEW(xml, off+3*4);
        int nameNsSi = LEW(xml, off+4*4);
        int nameSi = LEW(xml, off+5*4);

        if (tag0 == startTag) { // XML START TAG
          int tag6 = LEW(xml, off+6*4);  // Expected to be 14001400
          int numbAttrs = LEW(xml, off+7*4);  // Number of Attributes to follow
          //int tag8 = LEW(xml, off+8*4);  // Expected to be 00000000
          off += 9*4;  // Skip over 6+3 words of startTag data
          String name = compXmlString(xml, sitOff, stOff, nameSi);
          if (groupNodeNames.indexOf(name) >= 0) {
            // Put things like 'uses-permissions' under the same node
            tr.selCreate(name, null);
          } else {
            tr.addSelect(name, null);
          }
          startTagLineNo = lineNo;

          // Look for the Attributes
          if (sb != null) prtIndentSb(sb, indent, "<"+name);
          for (int ii=0; ii<numbAttrs; ii++) {
            int attrNameNsSi = LEW(xml, off);  // AttrName Namespace Str Ind, or FFFFFFFF
            int attrNameSi = LEW(xml, off + 1 * 4);  // AttrName String Index
            int attrValueSi = LEW(xml, off + 2 * 4); // AttrValue Str Ind, or FFFFFFFF
            int attrFlags = LEW(xml, off + 3 * 4);
            int attrResId = LEW(xml, off + 4 * 4);  // AttrValue ResourceId or dup AttrValue StrInd
            off += 5 * 4;  // Skip over the 5 words of an attribute

            attrName = compXmlString(xml, sitOff, stOff, attrNameSi);
            if (attrValueSi == -1) {
              if (pRes!=null && attrResId>>24==0x7f) {
                attrValue = pRes.getResource(attrResId);
              } else {
                attrValue = "resourceID 0x" + Integer.toHexString(attrResId);
              }
            } else {
              attrValue = compXmlString(xml, sitOff, stOff, attrValueSi);
            }

            if (sb != null) sb.append(" "+attrName+"=\""+attrValue+"\"");
            if (name.compareTo("uses-permission") == 0) {
              // Use the value as the node name
              // Remove 'android.permission.' prefix if present
              if (attrValue.indexOf("android.permission.") == 0) {
                attrValue = attrValue.substring(19);  // Remove common prefix
              }
              tr.add(attrValue, null);
            } else if (attrName.compareTo("name")==0
            /*&& useNameAttrAsValue.indexOf(name)>=0*/) {
              // For things like action and category elements, use the value of 
              // 'name' attribute as the value of the element (don't add subnode)
              tr.changeValue(attrValue);
              // UIF ?? Is this filter in a activity, a reciever ??
              // Save list of 'actions' and 'categories'  UIF
            } else {
              tr.add(attrName, attrValue);
            }
          }
          if (sb != null) sb.append(">\n");

          indent++;

        } else if (tag0 == endTag) { // XML END TAG
          indent--;
          off += 6*4;  // Skip over 6 words of endTag data
          String name = compXmlString(xml, sitOff, stOff, nameSi);
          if (sb != null) {
            if (startTagLineNo == lineNo) {
              int ii = sb.lastIndexOf("\n");
              sb.deleteCharAt(ii);
              prtIndentSb(sb, 0, "</"+name+">\n");
            } else {
              prtIndentSb(sb, indent, "</"+name+">\n");
            }
          }

          // Collect information about Intents, Activities and Uses-permissions
          if (name.compareTo("intent-filter") == 0) {
            totIntents++;
            intentTr.addSelect(pkgName, null);
            intentTr.add("action", null);
            intentTr.add("category", null);

            // Accumulate all the actions, category and data values
            String action="", category="", data="", nname;
            Tree xtr = (Tree)tr.clone();  xtr.prepChildScan();
            while (xtr.next()) {
              if ((nname = xtr.name()).compareTo("action") == 0) {
                action += " "+xtr.value();
              } else if (nname.compareTo("category") == 0) {
                category += " "+xtr.value();
              } else if (nname.compareTo("data") == 0) {
                String scheme = xtr.value("scheme");
                if (scheme!=null) data += " scheme:"+scheme;
                String mime = xtr.value("mimeType");
                if (mime!=null) data = " mimeType:"+mime;
              }
            }
            xtr.parent();  // Move back up the tree (after scanning children)

            // Name of element enclosing intent-filter
            xtr.parent();  // Move up tree to element enclosing the itent-filter
            String ifType = xtr.name();  xtr.release();

            String json = "{ type: 'intent', pkgName: '"+pkgName+"', "
            +(action.length()>0 ? " action: '"+action.substring(1)+"'," : "")
            +(category.length()>0 ? " category: '"+category.substring(1)+"'," : "")
            +(data.length()>0 ? " data: '"+data.substring(1)+"'," : "")
            +" ifType: '"+ifType+"'}";
            intentTr.parent();  // Move back up the tree (for addSelect above)
            RunJScmd.cmd("append('intentList', "+json+")");

          } else if (name.compareTo("activity") == 0) {
            activityTr.add(pkgName, attrName);
            totActivities++;
            String json = "{ type: 'activity', pkgName: '"+pkgName
            +"', activity: '"+tr.value()+"'}";
            RunJScmd.cmd("append('activityList', "+json+")");

          } else if (name.compareTo("uses-permission") == 0) {
            totPermissions++;
            permissionTr.selCreate(attrValue, null);
            permissionTr.add(pkgName, null);
            permissionTr.parent();  // Step back up to the top
            String json = "{ type: 'permission', pkgName: '"+pkgName
            +"', usesPermission: '"+attrValue+"'}";
            RunJScmd.cmd("append('permissionList', "+json+")");
          }
          tr.parent();  // Step back up the NobTree

        } else if (tag0 == endDocTag) {  // END OF XML DOC TAG
          break;  // (normally ends at end of byte array)

        } else {
          prt("  Unrecognized tag code '"+Integer.toHexString(tag0)
              +"' at offset "+off);
          break;
        }
      } // end of while loop scanning tags and attributes of XML tree
    } catch (Exception ex) {
      prt("decompressXML ex: "+ex+"\n"+PkgExpUtil.stackTrace(ex));
    }
  } // end of decompressXML


  public String compXmlString(byte[] xml, int sitOff, int stOff, int strInd) {
    if (strInd < 0) return null;
    int strOff = stOff + LEW(xml, sitOff+strInd*4);
    return compXmlStringAt(xml, strOff);
  }


  public static String spaces = "                                             ";
  public void prtIndentSb(StringBuffer sb, int indent, String str) {
    sb.append(spaces.substring(0, Math.min(indent*2, spaces.length())));
    sb.append(str);
  } // end of prtIndentSb


  // compXmlStringAt -- Return the string stored in StringTable format at
  // offset strOff.  This offset points to the 16 bit string length, which 
  // is followed by that number of 16 bit (Unicode) chars.
  public String compXmlStringAt(byte[] arr, int strOff) {
    int strLen = arr[strOff+1]<<8&0xff00 | arr[strOff]&0xff;
    byte[] chars = new byte[strLen];
    for (int ii=0; ii<strLen; ii++) {
      chars[ii] = arr[strOff+2+ii*2];
    }
    return new String(chars);  // Hack, just use 8 byte chars
  } // end of compXmlStringAt


  // LEW -- Return value of a Little Endian 32 bit word from the byte array
  //   at offset off.
  public static int LEW(byte[] arr, int off) {
    return arr[off+3]<<24&0xff000000 | arr[off+2]<<16&0xff0000
      | arr[off+1]<<8&0xff00 | arr[off]&0xFF;
  } // end of LEW


// LEH-- Return value of a Little Endian 16 bit halfword from the byte array
//   at offset off.
public static int LEH(byte[] arr, int off) {
  return arr[off+1]<<8&0xff00 | arr[off]&0xFF;
} // end of LEH


public String showArray(ProviderInfo[] provs) {
    if (provs == null) return "(null)";
    StringBuffer sb = new StringBuffer();
    sb.append(provs.length+" requestedPermissions");
    if (showArrays) for (int ii=0; ii<provs.length; ii++) {
      ProviderInfo prov = provs[ii];
      sb.append("\n    perm"+ii+" "+prov);
    }
    return sb.toString();
  }


  public String showArray(String[] perms) {
    if (perms == null) return "(null)";
    StringBuffer sb = new StringBuffer();
    sb.append(perms.length+" requestedPermissions");
    if (showArrays) for (int ii=0; ii<perms.length; ii++) {
      String perm = perms[ii];
      sb.append("\n    perm"+ii+" "+perm);
    }
    return sb.toString();
  }


  public String showArray(ActivityInfo[] acts) {
    if (acts == null) return "(null)";
    StringBuffer sb = new StringBuffer();
    sb.append(acts.length+" activities");
    totActivities += acts.length;
    if (showArrays) for (int ii=0; ii<acts.length; ii++) {
      ActivityInfo act = acts[ii];
      sb.append("\n    act"+ii+" "+act);
    }
    return sb.toString();
  }

  
  private String showArray(PermissionInfo[] perms) {
    if (perms == null) return "(null)";
    StringBuffer sb = new StringBuffer();
    sb.append(perms.length+" permissions");
    if (showArrays) for (int ii=0; ii<perms.length; ii++) {
      PermissionInfo perm = perms[ii];
      sb.append("\n    perm"+ii+" "+perm);
    }
    return sb.toString();
  }
  
  //--------------------------------------------------------------------Classes
  public static class RunJScmd implements Runnable {
    public String cmd;
    boolean respond;
    public RunJScmd(String cmdArg) {
      this.cmd = cmd;
      this.respond = false;
    }
    public RunJScmd(String cmd, boolean respond) {
      this.cmd = cmd;
      this.respond = respond;
    }
    public void run() {  // Used when thread like BgThread or PkgSSMHndCmd calls RunJSCmd.cmd
      int ii = 0;
      while ((ii = cmd.indexOf('"', ii)) >= 0) { // Escape double quotes
        cmd = cmd.substring(0, ii)+"\\\""+cmd.substring(ii+1);
        ii += 2;  // Skip over the backslash and the double quote
      }
      String url = "javascript:jsCmd(\""+cmd+"\", "+respond+")";
      if (cmd.charAt(0)==']') {
        url = "javascript:"+cmd.substring(1);
        prt("runjscmd.run override: "+cmd.substring(1));
      }
      brView.loadUrl(url);
    }


    public static void cmd(String cmdStr) {
      cmd(cmdStr, false);
    }

    public static void cmd(String cmdStr, boolean respond) {
      // If we are already in the main thread, execute it synchronously
      if (mainThread == Thread.currentThread()) {
        int ii = 0;
        while ((ii = cmdStr.indexOf('"', ii)) >= 0) { // Escape double quotes
          cmdStr = cmdStr.substring(0, ii)+"\\\""+cmdStr.substring(ii+1);
          ii += 2;  // Skip over the backslash and the double quote
        }
        String url = "javascript:jsCmd(\""+cmdStr+"\", "+respond+")";
        brView.loadUrl(url);
        
      } else {
        brView.post(new RunJScmd(cmdStr, respond));
      }
    } // end of cmd


    public static String cmdReply(String cmdStr) {
      String resp = null;
      cmd(cmdStr, true);
      synchronized (jsBridge.jsSemaphore) {
        if (jsBridge.jsRespStr == null) try {
          jsBridge.jsSemaphore.wait(5000);
        } catch (InterruptedException iEx) {}
        resp = jsBridge.jsRespStr;
        jsBridge.jsRespStr = null;
      }
      return resp;
    } // end of cmdReply


  } // end of class RunJScmd
  
  
  // JsBridge -- WebView Javascript to Java bridge
  class JsBridge {
    public int cnt = 0;
    public Object jsSemaphore = new Object();
    public String jsRespStr = null;

    @android.webkit.JavascriptInterface
    public String jsResponse(String str) {
      //Log.i("JsBridge", "JSresp "+str);
      synchronized (jsSemaphore) {
        jsRespStr = jsRespStr==null ?(String)str : jsRespStr+'\n'+str;
        jsSemaphore.notify();  // PkgExpUtil.PkgSSMHndCmd.sendCmd is probably waiting
      }
      return String.valueOf(cnt++);
    } // end of jsResponse


    @android.webkit.JavascriptInterface
    public String prt(String str) {
      Log.i("JsBridge", str);
      return String.valueOf(cnt++);
    }

    @android.webkit.JavascriptInterface
    public String rebuildManifest(String pkgName) {
      bgThread.queue.add("buildManifest "+ pkgName+" true");
      return "ok";
    } // end of rebuildManifest
    
    public String slotty(String str) {
      Log.i("JsBridge", cnt+" "+str);
      return String.valueOf(cnt++);
    }
  } // end of JsBridge

  
  //---------------------------------------------------------PkgWebChromeClient
  public static class PkgWebChromeClient extends WebChromeClient{
    // Methods: onDefaultVideoPoster  getVideoLoadingProgressView
    //  getVisitedHistory  
    //  onExceededDatabaseQuota  onGeographicPermissionHidePrompt
    //  onHideCustomView  onProgressChanged  onReachedMaxAppCacheSize
    //  onReceivedIcon  
    
    
    public void onCloseWindow(WebView  window) {
      prt("onCloseWindow "+window);
    }
    
    public void onRecievedTitle(WebView view, String title) {
      prt("onRecievedTitle: "+title);
    }
    
    public boolean onCreateWindow(WebView  view, boolean dialog,
      boolean userGesture, Message  resultMsg) {
      prt("onCreatWindow: "+dialog+" "+userGesture+" "+resultMsg);
      return true;
    }

    public boolean onConsoleMessage(ConsoleMessage msg) {
      prt("| "+msg.message());
      //  +"  ("+msg.messageLevel()+", src "+msg.sourceId()+":"+msg.lineNumber()+")");
      return true;  // We consumed the msg, prevent normal processing.
    }
    
    public boolean onJsAlert(WebView  view, String  url, String  message,
      JsResult  result) {
      prt("onJsAlert "+url+" "+message);
      boolean rc = false;
      return rc;
    }
    
    
    public boolean onJsBeforeUnload(WebView  view, String  url,
      String  message, JsResult  result) {
      return true;
    }
    
    
    public boolean onJsConfirm(WebView  view, String  url, String  message,
      JsResult  result) {
      return true;
    }
    
    
    public boolean onJsTimeout() {
      return true;
    }

    public boolean onJsPrompt(WebView  view, String  url, String  message,
        String  defaultValue, JsPromptResult  result) {
      return true;
    }

    public void onReceivedTouchIconUrl(WebView  view, String  url,
      boolean precomposed) {
      prt("  onReceivedTouchIconUrl: "+url);
      return;
    }


    public void onRequestFocus(WebView  view) {
      return;
    }


    //public void onShowCustomView(View  view, -- CustomViewCallback requires API level 7
    //  WebChromeClient.CustomViewCallback  callback) {
    //  return;
    //}

    public void onProgressChanged(WebView view, int progress) {
      // Activities and WebViews measure progress with different scales.
      // The progress meter will automatically disappear when we reach 100%
      //activity.setProgress(progress * 1000);
    }
  } // end of class PkgWebChromeClient
  
  
  //-----------------------------------------------------------PkgWebViewClient
  public static class PkgWebViewClient extends WebViewClient {
    // Methods include: doUpdateVisitedHistory  onFormResubmission
    //  onReceivedSslError
    public void onLoadResource(WebView  view, String  url) {
      prt("onLoadResource: "+url);
    }
    
    
    public void onPageStarted(WebView  view, String  url, Bitmap  favicon) {
      prt("onPageStarted: "+url+" "+favicon);
    }
    
    
    public void onPageFinished(WebView  view, String  url) {
      prt("onPageFinished: Start bgThread");
      bgThread.start();
    }
    
    
    public void  onReceivedHttpAuthRequest(WebView  view,
      HttpAuthHandler  handler, String  host, String  realm) {
      prt("onReceivedAuthRequest: "+handler+" "+host+" "+realm);
    }

    
    public void onReceivedError(WebView view, int errorCode,
      String description, String failingUrl) {
      prt("onReceivedError: "+errorCode+" "+description+" "+failingUrl);
    }
    
    
    public void onScaleChanged(WebView  view, float oldScale, float newScale) {
      prt("onScaleChanged: "+oldScale+"->"+newScale);
    }
    
    
    public void onUnhandledKeyEvent(WebView  view, KeyEvent  event) {
      prt("onUnhandledKeyEvent: "+keyInfo(event));
    }
    
    
    public boolean shouldOverrideKeyEvent(WebView  view, KeyEvent  event) {
      prt("shouldOverrideKeyEvent: '"+keyInfo(event));
      return false;  // False, allow normal key handling
    }


    String[] keyAction = {"down", "up", "multi"};
    public String keyInfo(KeyEvent event) {
      String action = keyAction[event.getAction()];
      int fg = event.getFlags();
      String flags = ((fg&KeyEvent.FLAG_CANCELED)==0 ? "" : "canceled ")
        +((fg&KeyEvent.FLAG_CANCELED_LONG_PRESS)==0 ? "" : "cancLongPress ")
        +((fg&KeyEvent.FLAG_EDITOR_ACTION)==0 ? "" : "editorAct ")
        +((fg&KeyEvent.FLAG_FROM_SYSTEM)==0 ? "" : "fromSystem ")
        +((fg&KeyEvent.FLAG_KEEP_TOUCH_MODE)==0 ? "" : "keepTouchMode ")
        +((fg&KeyEvent.FLAG_LONG_PRESS)==0 ? "" : "longPress ")
        +((fg&KeyEvent.FLAG_SOFT_KEYBOARD)==0 ? "" : "softKbd ")
        +((fg&KeyEvent.FLAG_TRACKING)==0 ? "" : "tracking ")
        +((fg&KeyEvent.FLAG_VIRTUAL_HARD_KEY)==0 ? "" : "virtHardKey ")
        +((fg&KeyEvent.FLAG_WOKE_HERE)==0 ? "" : "workHere ");
      String keyInfo = event.getDisplayLabel()+"' action "+action
        +", flags "+flags+", descCont "+event.describeContents()
        +", kc "+event.getKeyCode();
      return keyInfo;
    }
    
    
    public boolean shouldOverrideUrlLoading(WebView  view, String  url) {
      prt("shouldOverrideUrlLoading: "+url);
      return false;  // Permit the changing of the URL
    }
  } // end of class PkgWebViewClient
  

  @Override
  public void onDownloadStart(String url, String userAgent,
    String contentDisposition, String mimetype, long contentLength) {
    prt("onDownloadStart "+url+" "+userAgent+" "+contentDisposition+" "
      +mimetype+" "+contentLength);
  } // end of onDownloadStart
  

  //----------------------------------------------------------------------DEBUG
// Initialize the Android directories and files for our use
//public static String freshDate = "11/01/12-20:00:00";
public static String baseDir = "/sdcard/ssm";  
public static File cwd = new File("/sdcard");  // Dir must exist before 'cmds'
public static StringBuffer initLog = new StringBuffer();
public static String env = "";
public static String[] cmds = {
  "mkdir /sdcard/ssm",
  "chmod 0777 /sdcard/ssm",
  "mkdir /sdcard/ssm/store",
  "chmod 0777 /sdcard/ssm/store",
  "mkdir /sdcard/ssm/console",
  "chmod 0777 /sdcard/ssm/console"
};

public static boolean initFiles() {
  int ii, rc;
  prt("FILE SETUP(or update): ");
  for (ii=0; ii<cmds.length; ii++) {
    initLog.append(cmds[ii]);  initLog.append(" >>>\n");
    rc = execOSCmd(cmds[ii], initLog, null, cwd);
  } // end of for loop executing the initialization commands

  // Copy nob objects from .apk /assets 
  AssetManager am = pkgExp.getAssets();
  try {
    String[] objList = am.list("store");  // List all files in /assets dir
    Bytes cont = new Bytes();
    for (ii=0;  ii<objList.length; ii++) {
      String fid = objList[ii];
      if (fid.indexOf(".nob", fid.length()-4)<0) continue; // Skip non- .nob files
      InputStream is = am.open("store/"+fid);
      cont.arr = new byte[is.available()];
      cont.end = is.read(cont.arr);
      writeFile(baseDir+"/store/"+fid, cont);
      initLog.append("  "+fid+" installed, "+cont.end+" bytes\n");    
    }
    prt(initLog+"\n(end of file init)\n\n");

    return true; 
  } catch (Exception ex) {
    prt(initLog+"\n\ninitFiles ex: "+ex);
    return false;
  }
} // end of initFiles


public static void writeFile(String fid, Bytes content) {
  boolean tryMkdir = true;
  if (fid.charAt(0) != '/') { fid = baseDir+"/"+fid; }
  File ff = new File(fid);      
  while (true) {
    try {
      FileOutputStream fos = new FileOutputStream(fid);
      fos.write(content.arr, content.off, content.length());
      fos.close();
      
    } catch (Exception ex) {
      if (tryMkdir) {  // On first error, try creating the base dirs.
        File parent = ff.getParentFile();
        boolean rc = parent.mkdirs();
        prt("    LBcmds.cmdUpload created directories '"+parent+"', rc="
          +rc);
        tryMkdir = false;  continue;
      }
      prt("  LBcmds.cmdUpload ex: "+ex+"\n");
    }
    break;
  } // end of while true, loop to allow retry of fos.write after mkdir
} // end of writeFile


public static String showViews(Object vwObj) {
  StringBuffer sb = new StringBuffer();
  if (vwObj instanceof View == false) {
    return "(Arg is not a View ("+vwObj+"))";
  }
  View vw = (View)vwObj;
  
  Rect rect = new Rect();
  vw.getDrawingRect(rect);
  sb.append("    rect "+rect.left+","+rect.top
    +" "+rect.width()+"x"+rect.height()+"\n");
  ViewParent vp = null;
  sb.append("VW: ");  sb.append(vw);  sb.append("\n"); 
  // Ascend to the top of the view hierarchy
  vp = vw.getParent();
  while (vp != null) {
    sb.append("  v--");  sb.append(vp);  sb.append("\n");
    vp = vp.getParent();
  }

  showViewTree((ViewGroup)vw.getRootView(), sb, 1);
  return sb.toString();
} // end of showView


public static StringBuffer showViewTree(View vg, StringBuffer sb, int indent) {
  sb.append("|"+spaces.substring(0, indent*2)+vg.getClass().getName()
    +" "+vg.getId()+" "+vg.getLeft()+","+vg.getTop()+" "
    +vg.getWidth()+"x"+vg.getHeight()+" "+vg.getContentDescription()+"\n");
  
  if (vg instanceof ViewGroup) {    
    for (int ii=0; ii<((ViewGroup)vg).getChildCount(); ii++) {
      showViewTree(((ViewGroup)vg).getChildAt(ii), sb, indent+1);      
    }
  }
  return sb;
} // end of showViewTree
//----------------------------------------------end of debug stuff
  
  
  public static void prt(String str) { System.out.println(str); }
} // end of PackageExplorer class
