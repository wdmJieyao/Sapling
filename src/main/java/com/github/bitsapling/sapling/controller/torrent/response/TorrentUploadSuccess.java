package com.github.bitsapling.sapling.controller.torrent.response;

import com.github.bitsapling.sapling.objects.ResponsePojo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

@EqualsAndHashCode(callSuper = true)
@Data
public class TorrentUploadSuccess extends ResponsePojo {
    private final String originalName;
    private final String name;
    private final long size;
    private final String infoHash;
    private final long id;

    public TorrentUploadSuccess(long id, @NotNull String infoHash, @Nullable MultipartFile multipartFile) {
        super(0);
        this.id = id;
        this.infoHash = infoHash;
        if (multipartFile != null) {
            this.originalName = multipartFile.getOriginalFilename();
            this.name = multipartFile.getName();
            this.size = multipartFile.getSize();
        } else {
            this.originalName = "null";
            this.name = "null";
            this.size = 0;
        }
    }
}
