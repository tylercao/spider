package name.dengchao.spider.spider;

import com.alibaba.fastjson.JSONObject;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.google.common.collect.Multimap;
import com.google.common.hash.BloomFilter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import name.dengchao.spider.domain.Processor;
import name.dengchao.spider.domain.Url;
import name.dengchao.spider.domain.UrlMatcher;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class Spider implements InitializingBean, Runnable {

    @Resource(name = "urlMap")
    Multimap<UrlMatcher, Url> map;

    @Autowired
    BloomFilter<CharSequence> filter;

    public long visitedUrlCnt = 0;

    @Resource(name = "urlQueue")
    private BlockingQueue<String> toVisitUrls;

    @Value("#{iter['counter.path']}")
    private String counterPath;

    @Override
    public void afterPropertiesSet() throws Exception {
        File f = new File(counterPath);
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            f.createNewFile();
            visitedUrlCnt = 0;
        } else {
            String val = StreamUtils.copyToString(new FileInputStream(counterPath), StandardCharsets.UTF_8);
            val = val == null || "".equals(val) ? "0" : val;
            visitedUrlCnt = Long.valueOf(val);
        }
    }

    private ThreadLocal<Map<String, String>> params = new ThreadLocal<>();

    @Setter
    private volatile boolean goon = true;

    final WebClient webClient = new WebClient();

    public void start() throws Exception {

        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        webClient.getOptions().setTimeout(10_000);


        if (params.get() == null) {
            params.set(new HashMap<>());
        }

        while (goon) {
            String url = toVisitUrls.take();
            if (filter.mightContain(url)) {
                continue;
            }
            params.get().put("url", url);
            URI uri = URI.create(url);
            Url urlParser = findParser(uri);
            if (urlParser == null) {
                log.warn("No parser matched, skip url: " + url);
                continue;
            }
            HtmlPage page = webClient.getPage(url);
            Thread.sleep(1000);
            List<Processor> processors = urlParser.getProcessors();
            JSONObject json = new JSONObject();
            json.put("url", url);
            json.put("date", new Date());
            for (Processor processor : processors) {
                if (processor.getXpath() != null) {
                    List<?> eles = page.getByXPath(processor.getXpath());
                    eles.forEach(e -> processElement(json, processor, e, uri));
                } else if (processor.getOp().equals("appendInfo")) {
                    json.put(processor.getTag(), params.get().get(processor.getVal()));
                }
            }
            if (json.keySet().size() > 0) {
                log.debug(json.toJSONString());
                urlParser.getSaver().getVal().save(json);
            }
            filter.put(url);
            visitedUrlCnt++;
//            webClient.close();
        }
    }

    private void processElement(JSONObject json, Processor processor, Object element, URI processingURI) {
        if (!(element instanceof HtmlElement)) {
            return;
        }
        HtmlElement anchor = (HtmlElement) element;
        if (processor.getOp().equals("grab")) {
            String toVisitLink = anchor.getAttribute(processor.getTag());
            toVisitLink = formatUrl(toVisitLink, processingURI);
            if (!filter.mightContain(toVisitLink)) {
                log.info("Find to visit link: " + toVisitLink);
                toVisitUrls.add(toVisitLink);
            } else {
                log.debug("Skip visited link: " + toVisitLink);
            }
        } else if (processor.getOp().equals("save")) {
            String data = json.getString(processor.getTag());
            if (data != null) {
                data = data + ",#!#~, " + anchor.getTextContent();
                json.put(processor.getTag(), data);
            } else {
                json.put(processor.getTag(), anchor.getTextContent());
            }
        }
    }

    String formatUrl(String toVisitLink, URI processingURI) {
        if (toVisitLink.startsWith("http")) {
            return toVisitLink;
        }
        if (toVisitLink.startsWith("/")) {
            toVisitLink = processingURI.getScheme() + "://" + processingURI.getHost() + toVisitLink;
        } else {
            String path = processingURI.getPath().equals("") ? "/" : processingURI.getPath();
            path = path.substring(0, path.lastIndexOf("/") + 1) + toVisitLink;
            toVisitLink = processingURI.getScheme() + "://" + processingURI.getHost() + path;
        }

        toVisitLink = convertToAbsPath(toVisitLink);

        return toVisitLink;
    }

    String convertToAbsPath(String toVisitLink) {
        while (toVisitLink.contains("/../")) {
            toVisitLink = toVisitLink.replaceFirst("/[^/]+/\\.\\./", "/");
            log.info(toVisitLink);
        }
        return toVisitLink;
    }

    Url findParser(URI uri) {

        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();

        UrlMatcher matcher = new UrlMatcher();
        matcher.setScheme(scheme);
        matcher.setHost(host);
        matcher.setPort(port);
        matcher.setPath(path);

        Collection<Url> urlParsers = map.get(matcher);

        Url urlParser = null;

        for (Url url2 : urlParsers) {
            log.info("Matching " + url2.getMatcher().getPath() + " to " + path);
            Pattern p = Pattern.compile(url2.getMatcher().getPath());
            Matcher m = p.matcher(path);
            if (m.find()) {
                urlParser = url2;
                log.debug("Matched.");
                break;
            }
        }
        if (urlParser == null) {
            log.warn("Cannot find parser for URL: " + uri);
            filter.put(uri.getRawPath());
        }
        return urlParser;
    }

    @Override
    public void run() {
        try {
            start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addTovisitUrl(String url) {
        toVisitUrls.add(url);
    }
}
