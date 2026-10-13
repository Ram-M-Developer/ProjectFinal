# BarberEase – Project Architecture & Design Documentation

BarberEase is a real-time full-stack web application for salons to manage physical chairs, digital booking appointments, and waiting queues. It uses a structured Model-View-Controller (MVC) layered architecture built on **Spring Boot 3 (Java 21)**, **MySQL (via Spring Data JPA)**, and **WebSockets** for real-time status updates.

---

## 🏛️ High-Level System Architecture

```mermaid
graph TD
    subgraph Client ["Client Presentation Layer (Browser)"]
        CA["Customer Dashboard JS"]
        WS["WebSocket Client"]
    end

    subgraph Security ["Security & Networking"]
        SF["Spring Security Filter Chain"]
        JWT["JwtTokenFilter"]
        CORS["CORS Configuration"]
    end

    subgraph Server ["Backend Application Layer (Spring Boot)"]
        C["REST Controllers"]
        S["Service Layer"]
        WSH["WebSocket Handler"]
    end

    subgraph Database ["Data Access & Storage"]
        R["Spring Data JPA Repositories"]
        DB[(MySQL Database)]
    end

    CA -->|HTTP Requests| CORS
    CORS --> SF
    SF --> JWT
    JWT --> C
    WS <-->|WebSocket Events| WSH
    C --> S
    S --> R
    R <--> DB
    S -->|Broadcasts Updates| WSH
```

---

## 🗃️ Database ER Diagram (JPA Entities)

```mermaid
erDiagram
    Customer {
        Long id PK
        string name
        string email
        string password
        string phone
    }

    Chair {
        Long id PK
        int chairNumber
        string name
        string status
        boolean isActive
    }

    Service {
        Long id PK
        string name
        string description
        int durationMinutes
        decimal price
        string category
        boolean isActive
    }

    Appointment {
        Long id PK
        Long customerId FK
        Long chairId FK
        Long serviceId FK
        date appointmentDate
        string timeSlot
        string status
        string tokenNumber
    }

    Queue {
        Long id PK
        Long customerId FK
        Long serviceId FK
        Long chairId FK
        int position
        string status
    }

    Token {
        Long id PK
        string tokenNumber
        string type
        string status
    }

    Customer ||--o{ Appointment : books
    Customer ||--o{ Queue : joins
    Chair ||--o{ Appointment : hosts
    Chair ||--o{ Queue : seats
    Service ||--o{ Appointment : contains
    Service ||--o{ Queue : requests
```

---

## 🔄 Real-Time Event Flow

BarberEase utilizes WebSockets to synchronize chair and queue states across client sessions:

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Customer (Client)
    participant Server as Spring Boot Server
    participant DB as MySQL DB
    participant WS as WebSocket Broadcaster

    Customer->>Server: PUT /api/chairs/:id/reserve (Auth Header)
    Server->>DB: Check availability & set reservation hold
    DB-->>Server: Hold saved
    Server->>WS: Broadcast "chair-update"
    WS-->>Customer: Real-time update to all connected clients
    Server-->>Customer: Return success response
```
