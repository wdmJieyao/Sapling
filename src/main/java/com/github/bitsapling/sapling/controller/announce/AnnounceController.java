package com.github.bitsapling.sapling.controller.announce;

import cn.dev33.satoken.stp.StpUtil;
import com.github.bitsapling.sapling.entity.Peer;
import com.github.bitsapling.sapling.entity.Torrent;
import com.github.bitsapling.sapling.entity.User;
import com.github.bitsapling.sapling.exception.FixedAnnounceException;
import com.github.bitsapling.sapling.exception.InvalidAnnounceException;
import com.github.bitsapling.sapling.exception.RetryableAnnounceException;
import com.github.bitsapling.sapling.service.AnnouncePerformanceMonitorService;
import com.github.bitsapling.sapling.service.AnnounceService;
import com.github.bitsapling.sapling.service.BlacklistClientService;
import com.github.bitsapling.sapling.service.CategoryService;
import com.github.bitsapling.sapling.service.PeerService;
import com.github.bitsapling.sapling.service.PromotionService;
import com.github.bitsapling.sapling.service.TorrentService;
import com.github.bitsapling.sapling.service.UserService;
import com.github.bitsapling.sapling.type.AnnounceEventType;
import com.github.bitsapling.sapling.util.BencodeUtil;
import com.github.bitsapling.sapling.util.BooleanUtil;
import com.github.bitsapling.sapling.util.IPUtil;
import com.github.bitsapling.sapling.util.InfoHashUtil;
import com.github.bitsapling.sapling.util.MiscUtil;
import com.github.bitsapling.sapling.util.RandomUtil;
import com.github.bitsapling.sapling.util.SafeUUID;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@Slf4j
public class AnnounceController {
    private static final Random random = new Random();
    private static final Pattern infoHashPattern = Pattern.compile("info_hash=(.*?)($|&)");
    private static final Pattern peerIdPattern = Pattern.compile("peer_id=(.*?)&");
    private static final InetAddressValidator ipValidator = InetAddressValidator.getInstance();
    private static final int MIN_INTERVAL = 60 * 60 * 15;
    private static final int MAX_INTERVAL = 60 * 60 * 45;
    @Autowired
    private HttpServletRequest request;
    @Autowired
    private BlacklistClientService blacklistClientService;
    @Autowired
    private PeerService peerService;
    @Autowired
    private AnnounceService announceBackgroundJob;
    @Autowired
    private UserService userService;
    @Autowired
    private TorrentService torrentService;
    @Autowired
    private PromotionService promotionService;
    @Autowired
    private AnnouncePerformanceMonitorService performanceMonitorService;
    @Autowired
    private CategoryService categoryService;


    @GetMapping("/scrape")
    public ResponseEntity<String> scrape(@RequestParam Map<String, String> gets) throws FixedAnnounceException {
        // https://wiki.vuze.com/w/Scrape
        log.debug("Scrape Query String: {}", request.getQueryString());
        String passkey = gets.get("passkey");
        if (StringUtils.isEmpty(passkey)) {
            throw new InvalidAnnounceException("You must re-download the torrent from tracker for seeding.");
        }
        if (!SafeUUID.isUUID(passkey)) {
            throw new InvalidAnnounceException("Invalid passkey.");
        }
        checkClient();
        checkScrapeFields(gets);
        User user = userService.getUserByPasskey(passkey);
        if (user == null) {
            throw new InvalidAnnounceException("Unauthorized");
        }
        if (!StpUtil.hasPermission(user.getId(), "torrent:scrape")) {
            throw new InvalidAnnounceException("Permission Denied");
        }
        Map<String, Object> dict = new LinkedHashMap<>();
        Map<String, Integer> flags = new LinkedHashMap<>();
        flags.put("min_request_interval", randomInterval());
        dict.put("flags", flags);
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (String infoHash : readAllInfoHash(request.getQueryString())) {
            Torrent torrent = torrentService.getTorrent(infoHash);
            if (torrent == null) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            PeerStatus peerStatus = getPeerStatus(infoHash);
            meta.put("downloaded", peerStatus.downloaded());
            meta.put("complete", peerStatus.complete());
            meta.put("incomplete", peerStatus.incomplete());
            files.put(infoHash, meta);
        }
        dict.put("files", files);
        String resp = BencodeUtil.convertToString(BencodeUtil.bittorrent().encode(dict));
        log.debug("Base64 Response: {}", Base64.getEncoder().encodeToString(resp.getBytes(StandardCharsets.ISO_8859_1)));
        return ResponseEntity.ok()
                .header("Content-Type", "text/plain; charset=iso-8859-1")
                .body(resp);
    }


    @GetMapping("/announce")
    public ResponseEntity<String> announce(@RequestParam Map<String, String> gets) throws FixedAnnounceException, RetryableAnnounceException {
        long ns = System.nanoTime();
        log.debug("Query String: {}", request.getQueryString());
        String[] ipv4 = request.getParameterValues("ipv4");
        String[] ipv6 = request.getParameterValues("ipv6");
        String passkey = gets.get("passkey");
        if (StringUtils.isEmpty(passkey)) {
            throw new InvalidAnnounceException("You must re-download the torrent from tracker for seeding.");
        }
        if (!SafeUUID.isUUID(passkey)) {
            throw new InvalidAnnounceException("Invalid passkey.");
        }
        checkClient();
        checkAnnounceFields(gets);
        String peerId = gets.get("peer_id");
        long left = Long.parseLong(gets.get("left"));
        int port = Integer.parseInt(gets.get("port"));
        AnnounceEventType event = AnnounceEventType.fromName(gets.get("event"));
        int numWant = Integer.parseInt(Optional.ofNullable(MiscUtil.anyNotNull(gets.get("numwant"), gets.get("num want"), gets.get("num_want"))).orElse("150"));
        numWant = Math.min(numWant, 300);
        boolean noPeerId = BooleanUtil.parseBoolean(Optional.ofNullable(MiscUtil.anyNotNull(gets.get("nopeerid"), gets.get("no_peerid"), gets.get("no_peer_id"))).orElse("0"));
        boolean supportCrypto = BooleanUtil.parseBoolean(Optional.ofNullable(MiscUtil.anyNotNull(gets.get("supportcrypto"), gets.get("support crypto"), gets.get("support_crypto"))).orElse("0"));
        boolean compact = BooleanUtil.parseBoolean(gets.get("compact"));
        String peerIp = Optional.ofNullable(MiscUtil.anyNotNull(gets.get("ip"), gets.get("address"), gets.get("ipaddress"), gets.get("ip_address"), gets.get("ip address"))).orElse(IPUtil.getRequestIp(request));
        String infoHash = InfoHashUtil.parseInfoHash(readInfoHash(request.getQueryString()));
        long downloaded = Math.max(0, Long.parseLong(gets.get("downloaded")));
        long uploaded = Math.max(0, Long.parseLong(gets.get("uploaded")));
        int redundant = Integer.parseInt(Optional.ofNullable(MiscUtil.anyNotNull(gets.get("redundant"), gets.get("redundant_peers"), gets.get("redundant peers"), gets.get("redundant_peers"))).orElse("0"));
        // User permission checks
        log.debug("Passkey: " + passkey);
        User user = userService.getUserByPasskey(passkey);
        if (user == null) {
            throw new InvalidAnnounceException("Unauthorized");
        }
        if (!StpUtil.hasPermission(user.getId(), "torrent:announce")) {
            throw new InvalidAnnounceException("Permission Denied");
        }
        Torrent torrent = torrentService.getTorrent(infoHash);
        if (torrent == null) {
            throw new InvalidAnnounceException("Torrent not registered on this tracker");
        }
        // User had permission to announce torrents
        // Create an announce tasks and drop into background, end this request as fast as possible
        Set<String> peerIps = new HashSet<>();
        peerIps.add(peerIp);
        if (ipv4 != null) {
            peerIps.addAll(List.of(ipv4));
        }
        if (ipv6 != null) {
            peerIps.addAll(List.of(ipv6));
        }
        List<String> filteredIps = peerIps.stream().filter(this::checkValidIp).toList();
        if (filteredIps.isEmpty()) {
            log.info("Client {} announced invalid ips.", user.getUsername());
            throw new InvalidAnnounceException("Invalid IP address");
        }
        for (String filteredIp : filteredIps) {
            announceBackgroundJob.handleTask(new AnnounceService.AnnounceTask(filteredIp, port, infoHash, peerId, uploaded, downloaded, left, event, numWant, user.getId(), compact, noPeerId, supportCrypto, redundant, request.getHeader("User-Agent"), passkey, torrent.getId()));
        }
        String peers = BencodeUtil.convertToString(BencodeUtil.bittorrent().encode(generatePeersResponse(infoHash, numWant, compact)));
        log.debug("Base64 Response: {}", Base64.getEncoder().encodeToString(peers.getBytes(StandardCharsets.ISO_8859_1)));
        performanceMonitorService.recordStats(System.nanoTime() - ns);
        return ResponseEntity.ok()
                .header("Content-Type", "text/plain; charset=iso-8859-1")
                .body(peers);
    }

    @Nullable
    private Torrent testAddTorrent(@NotNull String infoHash) {
        log.info("TEST ONLY: Creating torrent for info_hash: {}", infoHash);
        Torrent torrent = new Torrent(
                0,
                infoHash,
                userService.getUser(1),
                "测试 torrent 标题" + UUID.randomUUID(),
                "测试 torrent 副标题" + UUID.randomUUID(),
                0, 0,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                false,
                false,
                categoryService.getCategory(1),
                promotionService.getDefaultPromotionPolicy(),
                "测试描述" + UUID.randomUUID()
        );
        return torrentService.save(torrent);
    }

    @Nullable
    private String readInfoHash(@NotNull String queryString) {
        // This is a workaround for binary encoded info_hash data.
        String[] queryStrings = queryString.split("&");
        for (String string : queryStrings) {
            String[] args = string.split("=");
            if (args.length != 2) {
                continue;
            }
            String key = args[0];
            if (key.equals("info_hash")) {
                return args[1];
            }
        }
        return null;
    }

    private @NotNull List<String> readAllInfoHash(@NotNull String queryString) {
        // This is a workaround for binary encoded info_hash data.
        List<String> infoHash = new ArrayList<>();
        String[] queryStrings = queryString.split("&");
        for (String string : queryStrings) {
            String[] args = string.split("=");
            if (args.length != 2) {
                continue;
            }
            String key = args[0];
            if (key.equals("info_hash")) {
                infoHash.add(args[1]);
            }
        }
        return infoHash;
    }

    @SneakyThrows(UnknownHostException.class)
    private boolean checkValidIp(@NotNull String ip) {
        InetAddress address = InetAddress.getByName(ip);
        return !address.isAnyLocalAddress()
                && !address.isLinkLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isSiteLocalAddress();
    }

    private void checkClient() throws FixedAnnounceException {
        String method = request.getMethod();
        if (!method.equals("GET")) {
            throw new InvalidAnnounceException("Invalid request method: " + method);
        }
        if (request.getHeader("User-Agent") == null) {
            throw new InvalidAnnounceException("Bad client: User-Agent cannot be empty");
        }
    }

    private void checkScrapeFields(@NotNull Map<String, String> gets) throws InvalidAnnounceException {
        if (StringUtils.isEmpty(gets.get("info_hash"))) throw new InvalidAnnounceException("Missing param: info_hash");
    }


    private void checkAnnounceFields(@NotNull Map<String, String> gets) throws InvalidAnnounceException {
        if (StringUtils.isEmpty(gets.get("info_hash"))) throw new InvalidAnnounceException("Missing param: info_hash");
        if (StringUtils.isEmpty(gets.get("peer_id"))) throw new InvalidAnnounceException("Missing param: peer_id");
        if (StringUtils.isEmpty(gets.get("port")) || !StringUtils.isNumeric(gets.get("port")))
            throw new InvalidAnnounceException("Missing/Invalid param: port");
        if (StringUtils.isEmpty(gets.get("uploaded")) || !StringUtils.isNumeric(gets.get("uploaded")))
            throw new InvalidAnnounceException("Missing/Invalid param: uploaded");
        if (StringUtils.isEmpty(gets.get("downloaded")) || !StringUtils.isNumeric(gets.get("downloaded")))
            throw new InvalidAnnounceException("Missing/Invalid param: downloaded");
        if (StringUtils.isEmpty(gets.get("left")) || !StringUtils.isNumeric(gets.get("left")))
            throw new InvalidAnnounceException("Missing/Invalid param: left");
        String numwant = MiscUtil.anyNotNull(gets.get("numwant"), gets.get("num want"), gets.get("num_want"));
        if (!StringUtils.isEmpty(numwant) && !StringUtils.isNumeric(numwant))
            throw new InvalidAnnounceException("Invalid optional param: numwant");
        if (!StringUtils.isEmpty(gets.get("compact")) && !StringUtils.isNumeric(gets.get("compact")))
            throw new InvalidAnnounceException("Invalid optional param: compact");
        if (!StringUtils.isEmpty(gets.get("corrupt")) && !StringUtils.isNumeric(gets.get("corrupt")))
            throw new InvalidAnnounceException("Invalid optional param: corrupt");
    }

    @NotNull
    private Map<String, Object> generatePeersResponse(String infoHash, int numWant, boolean compact) throws RetryableAnnounceException {
        Map<String, Object> resp;
        if (compact) {
            resp = generatePeersResponseCompat(infoHash, numWant);
        } else {
            resp = generatePeersResponseNonCompat(infoHash, numWant, compact);
        }
        return resp;
    }

    @NotNull
    private Map<String, Object> generatePeersResponseCompat(String infoHash, int numWant) throws RetryableAnnounceException {
        PeerResult peers = gatherPeers(infoHash, numWant);
        Map<String, Object> dict = new HashMap<>();
        dict.put("interval", randomInterval());
        dict.put("complete", peers.complete());
        dict.put("incomplete", peers.incomplete());
        dict.put("peers", BencodeUtil.compactPeers(peers.peers(), false));
        if (peers.peers6().size() > 0) {
            dict.put("peers6", BencodeUtil.compactPeers(peers.peers6(), true));
        }
        return dict;
    }

    @NotNull
    private Map<String, Object> generatePeersResponseNonCompat(@NotNull String infoHash, int numWant, boolean noPeerId) {
        PeerResult peers = gatherPeers(infoHash, numWant);
        List<Map<String, Object>> peerList = new ArrayList<>();
        List<Peer> allPeers = new ArrayList<>(peers.peers());
        allPeers.addAll(peers.peers6());
        for (Peer peer : allPeers) {
            Map<String, Object> peerMap = new LinkedHashMap<>();
            if (!noPeerId) {
                peerMap.put("peer id", peer.getPeerId());
            }
            peerMap.put("ip", peer.getIp());
            peerMap.put("port", peer.getPort());
            peerList.add(peerMap);
        }
        Map<String, Object> dict = new HashMap<>();
        dict.put("interval", randomInterval());
        dict.put("complete", peers.complete());
        dict.put("incomplete", peers.incomplete());
        dict.put("peers", peerList);
        return dict;
    }

    @NotNull
    private PeerResult gatherPeers(@NotNull String infoHash, int numWant) {
        List<Peer> allPeers = peerService.getPeers(infoHash);
        List<Peer> torrentPeers = RandomUtil.getRandomElements(allPeers, numWant);
        List<Peer> v4 = torrentPeers.stream().filter(peer -> ipValidator.isValidInet4Address(peer.getIp())).toList();
        List<Peer> v6 = torrentPeers.stream().filter(peer -> ipValidator.isValidInet6Address(peer.getIp())).toList();
        long completed = torrentPeers.stream().filter(Peer::isSeeder).count();
        long incompleted = torrentPeers.size() - completed;
        return new PeerResult(v4, v6, completed, incompleted);
    }

    private int randomInterval() {
        return random.nextInt(MIN_INTERVAL, MAX_INTERVAL);
    }

    @NotNull
    public PeerStatus getPeerStatus(@NotNull String infoHash) {
        List<Peer> peers = peerService.getPeers(infoHash);
        int complete = (int) peers.stream().filter(Peer::isSeeder).count();
        int incomplete = (int) peers.stream().filter(peer -> !peer.isSeeder()).count();
        Torrent torrent = torrentService.getTorrent(infoHash);
        int downloaded;
        if (torrent != null) {
            downloaded = (int) torrent.getFinishes();
        } else {
            downloaded = 0;
        }
        return new PeerStatus(complete, incomplete, downloaded);
    }

    record PeerResult(@NotNull List<Peer> peers, List<Peer> peers6, long complete, long incomplete) {
    }

    record PeerStatus(int complete, int incomplete, int downloaded) {

    }
}
