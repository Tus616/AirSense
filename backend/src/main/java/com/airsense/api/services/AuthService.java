package com.airsense.api.services;

import com.airsense.api.dto.LoginRequest;
import com.airsense.api.dto.LoginResponse;
import com.airsense.api.dto.RegisterRequest;
import com.airsense.api.dto.UserDto;
import com.airsense.api.entities.User;
import com.airsense.api.repositories.UserRepository;
import com.airsense.api.security.JwtTokenProvider;
import com.airsense.api.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthService implements UserDetailsService {

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public LoginResponse register(RegisterRequest request) {
        String name = request.getName() == null
                ? ""
                : request.getName().trim();

        String email = request.getEmail() == null
                ? ""
                : request.getEmail().trim().toLowerCase();

        String password = request.getPassword() == null
                ? ""
                : request.getPassword();

        if (name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }

        if (email.isBlank()
                || !email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new IllegalArgumentException("Valid email is required");
        }

        if (password.length() < 8) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters"
            );
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException(
                    "An account with this email already exists"
            );
        }

        User user = User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode(password))
                .role("CITIZEN")
                .vulnerable(false)
                .conditions(List.of())
                .channels(List.of("inApp"))
                .build();

        User savedUser = userRepository.save(user);

        return createLoginResponse(savedUser);
    }

    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail() == null
                ? ""
                : request.getEmail().trim().toLowerCase();

        String password = request.getPassword() == null
                ? ""
                : request.getPassword();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new BadCredentialsException("Invalid email or password")
                );

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return createLoginResponse(user);
    }

    private LoginResponse createLoginResponse(User user) {
        UserPrincipal principal = UserPrincipal.create(user);

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        String token = tokenProvider.generateToken(authentication);

        return LoginResponse.builder()
                .token(token)
                .user(new UserDto(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getRole()
                ))
                .role(user.getRole())
                .expiresIn(86400000L)
                .build();
    }

    public UserDto getMe(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new UsernameNotFoundException("User not found")
                );

        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole()
        );
    }

    @Override
    public UserDetails loadUserByUsername(String userId)
            throws UsernameNotFoundException {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "User not found with id: " + userId
                        )
                );

        return UserPrincipal.create(user);
    }
}