package com.maaitlunghau.__spring_boot_blueprint.module.user.entity;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.maaitlunghau.__spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "users")
@Getter
public class User extends BaseEntity implements UserDetails {

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, unique = true, length = 255)
    private String email; 

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = true, name = "image_url")
    private String imageUrl;

    protected User() {
    }

    public User(String fullName, String email, String password, Role role) {
        this.fullName = fullName;
        this.email = email;
        this.password = password;
        this.role = role;
    }

    public void updateProfile(String fullName, String imageUrl) {
        this.fullName = fullName;
        this.imageUrl = imageUrl;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    /**
     * default: true
     */
    // @Override
    // public boolean isAccountNonExpired() {
    // return UserDetails.super.isAccountNonExpired();
    // }

    // @Override
    // public boolean isAccountNonLocked() {
    // return UserDetails.super.isAccountNonLocked();
    // }

    // @Override
    // public boolean isCredentialsNonExpired() {
    // return UserDetails.super.isCredentialsNonExpired();
    // }
}
