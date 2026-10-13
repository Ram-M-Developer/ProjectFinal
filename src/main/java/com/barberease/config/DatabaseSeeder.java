package com.barberease.config;

import com.barberease.models.Admin;
import com.barberease.models.Chair;
import com.barberease.models.Service;
import com.barberease.repositories.AdminRepository;
import com.barberease.repositories.ChairRepository;
import com.barberease.repositories.ServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ChairRepository chairRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        seedAdmin();
        seedServices();
        seedChairs();
    }

    private void seedAdmin() {
        if (adminRepository.findByUsername("admin").isEmpty()) {
            Admin admin = new Admin();
            admin.setUsername("admin");
            admin.setEmail("admin@barberease.com");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setFullName("System Administrator");
            admin.setRole("super_admin");
            admin.setActive(true);
            adminRepository.save(admin);
            System.out.println("👤 Default admin account seeded (username: admin, password: admin123)");
        }
    }

    private void seedServices() {
        if (serviceRepository.count() == 0) {
            List<Service> services = new ArrayList<>();

            services.add(createService("Haircut",
                    "Professional haircut and styling tailored to your preference.",
                    30, new BigDecimal("120.00"), "haircut"));

            services.add(createService("Shaving",
                    "Classic clean shave or beard trim with hot towel treatment.",
                    20, new BigDecimal("80.00"), "shave"));

            services.add(createService("Both (Haircut & Shaving)",
                    "Complete grooming combo package including precision haircut and clean shave.",
                    45, new BigDecimal("200.00"), "combo"));

            services.add(createService("Facial",
                    "Deep cleansing, exfoliating steam facial and rejuvenating treatment.",
                    30, new BigDecimal("300.00"), "facial"));

            serviceRepository.saveAll(services);
            System.out.println("✂️ Grooming services seeded successfully (" + services.size() + " services created)");
        } else {
            List<Service> existing = serviceRepository.findAll();
            for (Service s : existing) {
                if ("Haircut".equalsIgnoreCase(s.getName())) {
                    s.setPrice(new BigDecimal("120.00"));
                } else if ("Shaving".equalsIgnoreCase(s.getName())) {
                    s.setPrice(new BigDecimal("80.00"));
                } else if ("Both (Haircut & Shaving)".equalsIgnoreCase(s.getName())) {
                    s.setPrice(new BigDecimal("200.00"));
                } else if ("Facial".equalsIgnoreCase(s.getName())) {
                    s.setPrice(new BigDecimal("300.00"));
                }
            }
            serviceRepository.saveAll(existing);
            System.out.println("✂️ Grooming service rates updated (Haircut: 120, Shaving: 80, Combo: 200, Facial: 300)");
        }
    }

    private void seedChairs() {
        for (int i = 1; i <= 6; i++) {
            final int num = i;
            Chair chair = chairRepository.findByChairNumber(num).orElse(null);
            if (chair == null) {
                chair = new Chair();
                chair.setChairNumber(num);
                chair.setStatus("available");
            }
            if (num <= 2) {
                chair.setName("Seat " + num);
                chair.setChairType("main");
            } else {
                chair.setName("Waiting Chair " + (num - 2));
                chair.setChairType("waiting");
            }
            chair.setActive(true);
            chairRepository.save(chair);
        }

        List<Chair> allChairs = chairRepository.findAll();
        for (Chair c : allChairs) {
            if (c.getChairNumber() > 6) {
                c.setActive(false);
                chairRepository.save(c);
            }
        }
        System.out.println("💺 Configured 2 Main Service Chairs & 4 Waiting Chairs successfully");
    }

    private Service createService(String name, String desc, int duration, BigDecimal price, String cat) {
        Service s = new Service();
        s.setName(name);
        s.setDescription(desc);
        s.setDurationMinutes(duration);
        s.setPrice(price);
        s.setCategory(cat);
        s.setActive(true);
        return s;
    }
}
