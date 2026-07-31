package vatm.aerosync.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "permit_training_profile_events")
public class PermitTrainingProfileEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "training_profile_id", nullable = false)
    private PermitTrainingProfileVersion trainingProfile;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 100)
    private String actor;

    @Lob
    @Column(name = "event_detail")
    private String eventDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public PermitTrainingProfileVersion getTrainingProfile() {
        return trainingProfile;
    }

    public void setTrainingProfile(PermitTrainingProfileVersion trainingProfile) {
        this.trainingProfile = trainingProfile;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getEventDetail() {
        return eventDetail;
    }

    public void setEventDetail(String eventDetail) {
        this.eventDetail = eventDetail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
