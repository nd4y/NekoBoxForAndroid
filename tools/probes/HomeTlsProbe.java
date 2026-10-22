import java.net.*;
import java.io.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
import org.json.*;

/** Private device diagnostics using unchanged Android system TLS trust. */
public class HomeTlsProbe {
 static class TrackedFactory extends SSLSocketFactory {
  final SSLSocketFactory delegate=(SSLSocketFactory)SSLSocketFactory.getDefault();
  final List<Socket> sockets=new ArrayList<>();
  Socket track(Socket s){sockets.add(s);return s;}
  public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
  public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
  public Socket createSocket(Socket s,String h,int p,boolean close)throws IOException{return track(delegate.createSocket(s,h,p,close));}
  public Socket createSocket(String h,int p)throws IOException{return track(delegate.createSocket(h,p));}
  public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return track(delegate.createSocket(h,p,l,lp));}
  public Socket createSocket(InetAddress h,int p)throws IOException{return track(delegate.createSocket(h,p));}
  public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return track(delegate.createSocket(h,p,l,lp));}
  public Socket createSocket()throws IOException{return track(delegate.createSocket());}
 }
 static String hash(byte[] data)throws Exception{StringBuilder b=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(data))b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
 public static void main(String[] args)throws Exception{
  URL url=new URL(args[0]);int count=Integer.parseInt(args[1]);long pause=Long.parseLong(args[2]);
  for(int sample=1;sample<=count;sample++){
   JSONObject r=new JSONObject();long start=System.nanoTime();r.put("sample",sample);r.put("timeMs",System.currentTimeMillis());
   TrackedFactory f=new TrackedFactory();HttpsURLConnection c=null;
   try{
    c=(HttpsURLConnection)url.openConnection();c.setSSLSocketFactory(f);c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
    int status=c.getResponseCode();r.put("httpStatus",status);r.put("tlsVerified",true);r.put("cipher",c.getCipherSuite());
    JSONArray chain=new JSONArray();for(java.security.cert.Certificate cert:c.getServerCertificates()){
     X509Certificate x=(X509Certificate)cert;JSONObject v=new JSONObject();v.put("sha256",hash(x.getEncoded()));v.put("subject",x.getSubjectX500Principal().getName());v.put("issuer",x.getIssuerX500Principal().getName());v.put("notBeforeMs",x.getNotBefore().getTime());v.put("notAfterMs",x.getNotAfter().getTime());chain.put(v);
    }r.put("certificates",chain);
    InputStream in=status<400?c.getInputStream():c.getErrorStream();if(in!=null){try(InputStream input=in){byte[] b=new byte[2048];r.put("bodySampleBytes",input.read(b));}}
   }catch(Exception e){r.put("tlsVerified",false);r.put("errorType",e.getClass().getSimpleName());JSONArray causes=new JSONArray();for(Throwable t=e;t!=null;t=t.getCause()){causes.put(t.toString());if(causes.length()>=8)break;}r.put("errorCauses",causes);StringWriter w=new StringWriter();e.printStackTrace(new PrintWriter(w));r.put("errorStack",w.toString());}
   finally{
    JSONArray sockets=new JSONArray();boolean vpn=false;for(Socket s:f.sockets){JSONObject v=new JSONObject();v.put("remote",String.valueOf(s.getRemoteSocketAddress()));v.put("local",String.valueOf(s.getLocalAddress()));if(s.getInetAddress()!=null)v.put("ipv6",s.getInetAddress() instanceof Inet6Address);try{NetworkInterface n=NetworkInterface.getByInetAddress(s.getLocalAddress());if(n!=null){v.put("interface",n.getName());vpn|=n.getName().startsWith("tun");}}catch(Exception ignored){}sockets.put(v);}r.put("sockets",sockets);r.put("vpnRoute",vpn);r.put("elapsedMs",(System.nanoTime()-start)/1000000);if(c!=null)c.disconnect();
   }
   try{JSONArray a=new JSONArray();for(InetAddress x:InetAddress.getAllByName(url.getHost()))a.put(x.getHostAddress());r.put("systemDns",a);}catch(Exception e){r.put("dnsErrorType",e.getClass().getSimpleName());}
   System.out.println(r.toString());System.out.flush();if(sample<count)Thread.sleep(pause);
  }
 }
}
