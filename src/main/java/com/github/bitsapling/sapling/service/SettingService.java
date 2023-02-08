package com.github.bitsapling.sapling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bitsapling.sapling.entity.SettingEntity;
import com.github.bitsapling.sapling.repository.SettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class SettingService {
    @Autowired
    private SettingRepository repository;
    @Autowired
    private ObjectMapper objectMapper;

    @Nullable
    @Contract("_, _, !null -> !null")
    public <T> T get(@NotNull String configKey, @NotNull Class<T> clazz, @Nullable T def) {
        Optional<SettingEntity> configData = repository.findByKey(configKey);
        if (configData.isEmpty()) {
            return def;
        }
        String data = configData.get().getValue();
        try {
            return objectMapper.readValue(data, clazz);
        } catch (JsonProcessingException e) {
            log.error("Unable to deserialize setting object: {} -> {}", configKey, data, e);
            return null;
        }
    }

    @Nullable
    public <T> T get(@NotNull String configKey, @NotNull Class<T> clazz) {
        return get(configKey, clazz, null);
    }

    public <T> void set(@NotNull String configKey, @Nullable T value) throws JsonProcessingException {
        if(value == null){
           repository.deleteByKey(configKey);
           return;
        }
        SettingEntity entity;
        Optional<SettingEntity> inDatabase = repository.findByKey(configKey);
        if(inDatabase.isPresent()){
            entity = inDatabase.get();
        }else{
            entity = new SettingEntity(0,configKey, objectMapper.writeValueAsString(value));
        }
        entity = repository.save(entity);
    }

}
