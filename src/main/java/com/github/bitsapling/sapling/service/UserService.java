package com.github.bitsapling.sapling.service;

import com.github.bitsapling.sapling.entity.UserEntity;
import com.github.bitsapling.sapling.objects.User;
import com.github.bitsapling.sapling.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;

@Service
public class UserService {
    @Autowired
    private UserRepository repository;
    @Autowired
    private UserGroupService userGroupService;

    // getUser
    @Nullable
    @Transactional
    public User getUser(long id) {
        Optional<UserEntity> userEntity = repository.findById(id);
        return userEntity.map(this::convert).orElse(null);
    }

    @Nullable
    @Transactional
    public User getUserByUsername(String username) {
        Optional<UserEntity> userEntity = repository.findByUsername(username);
        return userEntity.map(this::convert).orElse(null);
    }

    @Nullable
    @Transactional
    public User getUserByEmail(String email) {
        Optional<UserEntity> userEntity = repository.findByEmail(email);
        return userEntity.map(this::convert).orElse(null);
    }

    @Nullable
    @Transactional
    public User getUserByPasskey(String passkey) {
        Optional<UserEntity> userEntity = repository.findByPasskey(passkey);
        return userEntity.map(this::convert).orElse(null);
    }
    @Transactional
    public void save(User user) {
        UserEntity entity = convert(user);
        repository.save(entity);
    }

    @NotNull
    public UserEntity convert(User user) {
        return new UserEntity(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getUsername(),
                userGroupService.convert(user.getGroup()),
                user.getPasskey(),
                Timestamp.from(user.getCreatedAt()),
                user.getAvatar(),
                user.getCustomTitle(),
                user.getSignature(),
                user.getLanguage(),
                user.getDownloadBandwidth(),
                user.getUploadBandwidth(),
                user.getDownloaded(),
                user.getUploaded(),
                user.getRealDownloaded(),
                user.getRealUploaded(),
                user.getIsp(),
                user.getKarma(),
                user.getInviteSlot(),
                user.getSeedingTime().toMillis());
    }

    @NotNull
    public User convert(@NotNull UserEntity entity) {
        return new User(
                entity.getId(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getUsername(),
                userGroupService.convert(entity.getGroup()),
                entity.getPasskey(),
                entity.getCreatedAt().toInstant(),
                entity.getAvatar(),
                entity.getCustomTitle(),
                entity.getSignature(),
                entity.getLanguage(),
                entity.getDownloadBandwidth(),
                entity.getUploadBandwidth(),
                entity.getDownloaded(),
                entity.getUploaded(),
                entity.getRealDownloaded(),
                entity.getRealUploaded(),
                entity.getIsp(),
                entity.getKarma(),
                entity.getInviteSlot(),
                Duration.ofMillis(entity.getSeedingTime()));
    }
}
