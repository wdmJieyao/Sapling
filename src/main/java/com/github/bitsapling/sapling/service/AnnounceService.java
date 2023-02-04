package com.github.bitsapling.sapling.service;

import com.github.bitsapling.sapling.exception.AnnounceBusyException;
import com.github.bitsapling.sapling.objects.Peer;
import com.github.bitsapling.sapling.objects.Torrent;
import com.github.bitsapling.sapling.objects.User;
import com.github.bitsapling.sapling.type.AnnounceEventType;
import com.github.bitsapling.sapling.util.ExecutorUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

@Service
@Slf4j
public class AnnounceService {
    private final BlockingDeque<AnnounceTask> taskQueue = new LinkedBlockingDeque<>(4096);
    @Autowired
    private ExecutorUtil executor;
    @Autowired
    private UserService userService;
    @Autowired
    private PeerService peerService;
    @Autowired
    private TorrentService torrentService;

    public AnnounceService() {
        Thread thread = new Thread(() -> {
            try {
                handleTasks();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    public void schedule(@NotNull AnnounceTask announceTask) throws AnnounceBusyException {
        try {
            taskQueue.add(announceTask);
        } catch (IllegalStateException exception) {
            throw new AnnounceBusyException();
        }
    }

    public void handleTasks() throws InterruptedException {
        while (true) {
            AnnounceTask task = taskQueue.take();
            executor.getAnnounceExecutor().submit(() -> {
                try {
                    handleTask(task);
                } catch (Exception e) {
                    log.error("Error handling task: {}", task, e);
                }
            });
        }
    }

    private void handleTask(AnnounceTask task) throws NoSuchElementException {
        // Multi-threaded
        User user = task.user();
        // Register torrent into peers
        Peer peer = peerService.getPeer(task.ip(),task.port(), task.infoHash());
        if(peer == null){
            peer = createNewPeer(task);
        }

        Torrent torrent = torrentService.getTorrent(task.infoHash());
        if(torrent == null){
            throw new NoSuchElementException();
        }
        peer.setUploaded(task.uploaded());
        peer.setDownloaded(task.downloaded());
        peer.setLeft(task.left());
        peer.setSeeder(task.left() == 0);
        peer.setUpdateAt(Instant.now());
        torrentService.save(torrent);

        // Statistics and update of user data
        long uploadOffset = task.uploaded() - user.getRealUploaded();
        long downloadOffset = task.downloaded() - user.getRealDownloaded();
        // Client statistics reset?
        if (uploadOffset < 0) uploadOffset = task.uploaded();
        if (downloadOffset < 0) downloadOffset = task.downloaded();
        // Update real user data
        user.setRealDownloaded(user.getRealDownloaded() + downloadOffset);
        user.setRealUploaded(user.getRealUploaded() + uploadOffset);
        // Apply user promotion policy
        long promotionUploadOffset = user.getGroup().getPromotionPolicy().applyUploadRatio(uploadOffset);
        long promotionDownloadOffset = user.getGroup().getPromotionPolicy().applyDownloadRatio(downloadOffset);
        // Apply torrent promotion policy
        promotionUploadOffset = torrent.getPromotionPolicy().applyUploadRatio(promotionUploadOffset);
        promotionDownloadOffset = torrent.getPromotionPolicy().applyDownloadRatio(promotionDownloadOffset);
        user.setUploaded(user.getUploaded() + promotionUploadOffset);
        user.setDownloaded(user.getDownloaded() + promotionDownloadOffset);

        userService.save(user);
//        log.info("Updated user {}'s data: uploaded {}, downloaded {} with original data: actual-uploaded {}, actual-downloaded {}", user.getUsername(), promotionUploadOffset, promotionDownloadOffset, uploadOffset, downloadOffset);
        if(task.event() == AnnounceEventType.STOPPED){
            if(peer.getId() != 0) {
                peerService.delete(peer);
            }
        }
    }

    @NotNull
    private Peer createNewPeer(AnnounceTask task) {
        log.debug("Creating a new peer for: {}", task.infoHash());
       // TorrentEntity torrent = torrentRepository.findByInfoHash(task.infoHash()).orElseThrow();

        return new Peer(
                0,
                task.ip(),
                task.port(),
                task.infoHash(),
                task.peerId(),
                task.userAgent(),
                task.passKey(),
                task.uploaded(),
                task.downloaded(),
                task.left(),
                task.left() == 0,
                Instant.now()
        );
    }

    public record AnnounceTask(
            @NotNull String ip, int port, @NotNull String infoHash, @NotNull String peerId,
            long uploaded, long downloaded, long left, @NotNull AnnounceEventType event,
            int numWant, User user, boolean compact, boolean noPeerId,
            boolean supportCrypto, int redundant, String userAgent, String passKey
    ) {

    }
}
