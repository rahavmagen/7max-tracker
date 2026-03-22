package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.LessonEvent;
import com.sevenmax.tracker.entity.LessonRegistration;
import com.sevenmax.tracker.repository.LessonEventRepository;
import com.sevenmax.tracker.repository.LessonRegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LessonService {

    private final LessonEventRepository eventRepo;
    private final LessonRegistrationRepository regRepo;

    public Optional<LessonEvent> getUpcomingEvent() {
        return eventRepo.findFirstByEventDateGreaterThanEqualOrderByEventDateAsc(LocalDate.now());
    }

    @Transactional
    public LessonEvent setEvent(LocalDate date, String title) {
        LessonEvent event = getUpcomingEvent().orElse(new LessonEvent());
        event.setEventDate(date);
        if (title != null) event.setTitle(title);
        return eventRepo.save(event);
    }

    @Transactional
    public LessonRegistration register(Long eventId, String username, String fullName, String phone) {
        if (regRepo.existsByLessonEventIdAndUsername(eventId, username)) {
            throw new RuntimeException("Already registered");
        }
        LessonEvent event = eventRepo.findById(eventId).orElseThrow(() -> new RuntimeException("Event not found"));
        LessonRegistration reg = new LessonRegistration();
        reg.setLessonEvent(event);
        reg.setUsername(username);
        reg.setFullName(fullName);
        reg.setPhone(phone);
        return regRepo.save(reg);
    }

    @Transactional
    public void unregister(Long eventId, String username) {
        regRepo.findByLessonEventIdAndUsername(eventId, username).ifPresent(regRepo::delete);
    }

    public boolean isRegistered(Long eventId, String username) {
        return regRepo.existsByLessonEventIdAndUsername(eventId, username);
    }

    public List<LessonRegistration> getRegistrations(Long eventId) {
        return regRepo.findByLessonEventId(eventId);
    }
}
