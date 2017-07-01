package qiniu.predem.android.diagnosis;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

import qiniu.predem.android.bean.TelemetryBean;
import qiniu.predem.android.config.Configuration;
import qiniu.predem.android.config.HttpConfig;
import qiniu.predem.android.util.AsyncRun;
import qiniu.predem.android.util.LogUtils;

import static java.lang.Runtime.getRuntime;
import static qiniu.predem.android.util.MatcherUtil.getIpFromTraceMatcher;
import static qiniu.predem.android.util.MatcherUtil.ipMatcher;
import static qiniu.predem.android.util.MatcherUtil.timeMatcher;
import static qiniu.predem.android.util.MatcherUtil.traceMatcher;

/**
 * Created by Misty on 17/6/15.
 */

public class NetDiagnosis implements Task {
    public static final int TimeOut = -3;
    public static final int NotReach = -2;
    private static final String TAG = "NetDiagnosis";
    private static final int MaxHop = 31;
    private static final int MAX = 64 * 1024;

    private final String address;
    private final String url;
    private final Callback complete;
    private volatile boolean stopped;
    private Context mContext;

    private NetDiagnosis(Context context, String domainn, String url, Callback complete) {
        this.mContext = context;
        this.address = domainn;
        this.url = url;
        this.complete = complete;
        this.stopped = false;
    }

    public static void start(Context context, String domain, String url, Callback complete) {
        if (!Configuration.networkDiagnosis) {
            complete.complete(false, new Exception("the diagnosis isn't open"));
        }
        final NetDiagnosis p = new NetDiagnosis(context, domain, url, complete);
        AsyncRun.runInBack(new Runnable() {
            @Override
            public void run() {
                p.run();
            }
        });
    }

    private static String getIp(String host) throws UnknownHostException {
        InetAddress i = InetAddress.getByName(host);
        return i.getHostAddress();
    }

    private void run() {
        TelemetryBean result = new TelemetryBean();
        String ip = null;
        try {
            ip = getIp(address);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            //TODO ping 失败
            complete.complete(false, e);
        }
        String cmd = String.format(Locale.getDefault(), "ping -n -i %f -s %d -c %d %s", ((double) 200 / 1000), 56, 10, ip);
        Process process = null;
        StringBuilder str = new StringBuilder();
        BufferedReader reader = null;
        BufferedReader errorReader = null;
        try {
            process = getRuntime().exec(cmd);
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = reader.readLine()) != null) {
                str.append(line).append("\n");
            }
            while ((line = errorReader.readLine()) != null) {
                str.append(line);
            }
            reader.close();
            errorReader.close();
            process.waitFor();

        } catch (IOException e) {
            complete.complete(false, e);
            e.printStackTrace();
        } catch (InterruptedException e) {
            complete.complete(false, e);
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                if (process != null) {
                    process.destroy();
                }
            } catch (Exception e) {
                complete.complete(false, e);
                e.printStackTrace();
            }
        }
        //TODO 解析ping结果
        result.pingResult = new TelemetryBean.PingResult(str.toString(), ip, 56);

        ///////////////////////////////////////
        InetSocketAddress server = new InetSocketAddress(ip, 80);
        int[] times = new int[3];
        int index = -1;
        int dropped = 0;
        for (int i = 0; i < 3 && !stopped; i++) {
            long start = System.currentTimeMillis();
            try {
                connect(server, 20 * 1000);
            } catch (IOException e) {
                e.printStackTrace();
                int code = NotReach;
                if (e instanceof SocketTimeoutException) {
                    code = TimeOut;
                }
                if (i == 0) {
                    //TODO tcpping 失败
                    complete.complete(false, e);
//                    return null;
                } else {
                    dropped++;
                }
            }
            long end = System.currentTimeMillis();
            int t = (int) (end - start);
            times[i] = t;
            index = i;
            try {
                if (!stopped && 100 > t && t > 0) {
                    Thread.sleep(100 - t);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (index == -1) {
            //TODO tcpping 失败
            complete.complete(false, new Exception("tcp ping failure"));
//            return null;
        }
        // TODO: 17/6/8  解析 tcpping
        result.tcpResult = buildResult(times, index, ip, dropped);

        /////////////////////////////////////////////////
        int hop = 1;
        TelemetryBean.TraceRouteResult r = new TelemetryBean.TraceRouteResult(ip);
        Process p;
        while (hop < MaxHop && !stopped) {
            long t1 = System.currentTimeMillis();
            try {
                p = executePingCmd(ip, hop);
            } catch (IOException e) {
                e.printStackTrace();
                // TODO: 17/6/8 tr 失败
                complete.complete(false, e);
                break;
            }
            long t2 = System.currentTimeMillis();
            String pingtOutput = getPingtOutput(p);
            if (str.length() == 0) {
                // TODO: 17/6/8 tr 失败
                complete.complete(false, new Exception("TraceRoute failure"));
                break;
            }
            Matcher m = traceMatcher(pingtOutput);

            StringBuilder lineBuffer = new StringBuilder(256);
            lineBuffer.append(hop).append(".");
            if (m.find()) {
                r.append(printNormal(m, (t2 - t1) / 2, lineBuffer));
            } else {
                Matcher matchPingIp = ipMatcher(pingtOutput);
                if (matchPingIp.find()) {
                    r.append(printEnd(matchPingIp, pingtOutput, lineBuffer));
                    break;
                } else {
                    lineBuffer.append("\t\t * \t");
                }
            }
            hop++;
        }
        // TODO: 17/6/8 解析tr
        result.trResult = r;

        /////////////////////////////////////////////////
        String ips[] = DNS.local();
        String dip = DNS.check(url);
        String dnsr = "localDns : " + Arrays.toString(ips) + ";dns : " + dip;
        result.dnsRecords = dnsr;

        /////////////////////////////////////////////////
        long start = System.currentTimeMillis();
        try {
            URL u = new URL("http://" + address);
            HttpURLConnection httpConn = (HttpURLConnection) u.openConnection();
            httpConn.setConnectTimeout(10000);
            httpConn.setReadTimeout(20000);
            int responseCode = httpConn.getResponseCode();

            Map<String, List<String>> headers = httpConn.getHeaderFields();
            InputStream is = httpConn.getInputStream();
            int len = httpConn.getContentLength();
            len = len > MAX || len < 0 ? MAX : len;
            byte[] data = new byte[len];
            int read = is.read(data);
            long duration = System.currentTimeMillis() - start;
            is.close();
            if (read <= 0) {
                result.httpResult = new TelemetryBean.HttpResult(responseCode, headers, null, (int) duration, "no body");
            } else if (read < data.length) {
                result.httpResult = new TelemetryBean.HttpResult(responseCode, headers, null, (int) duration, "no body");
            }

            //上报
            sendRequest(HttpConfig.getDiagnosisUrl(), result.toJsonString());

//            complete.complete(res, null);
        } catch (IOException e) {
            e.printStackTrace();
            result.httpResult = new TelemetryBean.HttpResult(-1, null, null, 0, null);
            complete.complete(false, e);
        }
    }

    private boolean sendRequest(String url, String content) {
        LogUtils.d(TAG, "------url = " + url + "\ncontent = " + content);

        HttpURLConnection httpConn;
        try {
            httpConn = (HttpURLConnection) new URL(url).openConnection();
        } catch (IOException e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        } catch (Exception e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        }
        httpConn.setConnectTimeout(3000);
        httpConn.setReadTimeout(10000);
        try {
            httpConn.setRequestMethod("POST");
        } catch (ProtocolException e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        }
        httpConn.setRequestProperty("Content-Type", "application/json");
        httpConn.setRequestProperty("Accept-Encoding", "identity");

        try {
            byte[] bytes = content.getBytes();
            if (bytes == null) {
                complete.complete(false, new Exception("response body empty"));
                return false;
            }
            httpConn.getOutputStream().write(content.getBytes());
            httpConn.getOutputStream().flush();
        } catch (IOException e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        } catch (Exception e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        }
        int responseCode = 0;
        try {
            responseCode = httpConn.getResponseCode();
        } catch (IOException e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        }
        if (responseCode != 201) {
            complete.complete(false, new Exception("response error, code " + responseCode));
            return false;
        }
        int length = httpConn.getContentLength();
        if (length == 0) {
            complete.complete(false, new Exception("response body empty"));
            return false;
        } else if (length < 0) {
            length = 16 * 1024;
        }
        InputStream is;
        try {
            is = httpConn.getInputStream();
        } catch (IOException e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        } catch (Exception e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        }
        byte[] data = new byte[length];
        int read = 0;
        try {
            read = is.read(data);
        } catch (IOException e) {
            LogUtils.e(TAG, e.toString());
            complete.complete(false, e);
            return false;
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LogUtils.e(TAG, e.toString());
                complete.complete(false, e);
                return false;
            }
        }
        if (read <= 0) {
            complete.complete(false, new Exception("response body empty"));
            return false;
        }
        complete.complete(true, null);
        return true;
    }

    private TelemetryBean.TCPResult buildResult(int[] times, int index, String ip, int dropped) {
        int sum = 0;
        int min = 1000000;
        int max = 0;
        for (int i = 0; i <= index; i++) {
            int t = times[i];
            if (t > max) {
                max = t;
            }
            if (t < min) {
                min = t;
            }
            sum += t;
        }
        return new TelemetryBean.TCPResult(0, ip, max, min, sum / (index + 1), sum, 0, index + 1, dropped);
    }

    private String getPingtOutput(Process process) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream()));
        String line;
        StringBuilder text = new StringBuilder();
        try {
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        process.destroy();
        return text.toString();
    }

    private String printNormal(Matcher m, long time, StringBuilder lineBuffer) {
        String pingIp = getIpFromTraceMatcher(m);
        lineBuffer.append(pingIp);
        lineBuffer.append(time); // 近似值
        lineBuffer.append("ms");

        return lineBuffer.toString();
    }

    private String printEnd(Matcher m, String out, StringBuilder lineBuffer) {
        String pingIp = m.group();
        Matcher matcherTime = timeMatcher(out);
        if (matcherTime.find()) {
            String time = matcherTime.group();
            lineBuffer.append(pingIp);
            lineBuffer.append(time);
            return lineBuffer.toString();
        }
        return "";
    }

    private Process executePingCmd(String host, int hop) throws IOException {
        String command = "ping -n -c 1 -t " + hop + " " + host;
        return getRuntime().exec(command);
    }

    private void connect(InetSocketAddress socketAddress, int timeOut) throws IOException {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(socketAddress, timeOut);
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }

    public interface Callback {
        void complete(boolean isSuccessful, Exception e);
    }
}
