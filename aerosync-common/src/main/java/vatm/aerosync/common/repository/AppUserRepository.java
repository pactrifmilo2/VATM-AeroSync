package vatm.aerosync.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vatm.aerosync.common.entity.AppUser;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);
}
