package com.wordtrail.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Table("user_account")
public class UserAccount {
    @Id(keyType = KeyType.Auto)
    @ToString.Include
    private Long id;
    @ToString.Include
    private String username;
    @JsonIgnore
    @Column("password_hash")
    private String passwordHash;
    private String nickname;
    @Column("created_at")
    private LocalDateTime createdAt;
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
