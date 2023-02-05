package com.github.bitsapling.sapling.service;

import com.github.bitsapling.sapling.entity.PeerEntity;
import com.github.bitsapling.sapling.objects.Peer;
import com.github.bitsapling.sapling.repository.PeersRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Repository
@Transactional
@Slf4j
public class PeerService {
    @Autowired
    private PeersRepository repository;

    @Nullable
    @Transactional
    public Peer getPeer(@NotNull String ip, int port, @NotNull String infoHash) {
        PeerEntity entity = repository.findByIpAndPortAndInfoHash(ip, port, infoHash).orElse(null);
        if (entity == null) {
            return null;
        }
        return convert(entity);
    }

    @NotNull
    @Transactional
    public List<Peer> getPeers(@NotNull String infoHash) {
        List<PeerEntity> entities = repository.findPeersByInfoHash(infoHash);
        return entities.stream().map(this::convert).toList();
    }

    @Transactional
    public void save(@NotNull Peer peer) {
        repository.save(convert(peer));
    }

    @Transactional
    public void delete(@NotNull Peer peer) {
        //repository.delete(convert(peer));
        repository.deleteById(peer.getId());
    }

    @NotNull
    public Peer convert(@NotNull PeerEntity entity) {
        return new Peer(
                entity.getId(),
                entity.getIp(),
                entity.getPort(),
                entity.getInfoHash(),
                entity.getPeerId(),
                entity.getUserAgent(),
                entity.getPassKey(),
                entity.getUploaded(),
                entity.getDownloaded(),
                entity.getLeft(),
                entity.isSeeder(),
                entity.getUpdateAt(),
                entity.getSeedingTime()
        );
    }

    @NotNull
    public PeerEntity convert(@NotNull Peer peer) {
        return new PeerEntity(
                peer.getId(),
                peer.getIp(),
                peer.getPort(),
                peer.getInfoHash(),
                peer.getPeerId(),
                peer.getUserAgent(),
                peer.getUploaded(),
                peer.getDownloaded(),
                peer.getLeft(),
                peer.isSeeder(),
                peer.getPassKey(),
                peer.getUpdateAt(),
                peer.getSeedingTime()
        );
    }

    public int cleanup() {
        List<PeerEntity> entities = repository.findAllByUpdateAtLessThan(Instant.now().minus(90, ChronoUnit.MINUTES));
        int count = entities.size();
        repository.deleteAll(entities);
        return count;
    }
}
