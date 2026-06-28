package com.skybook.praveen.common.event;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailEvent {
    private String to;
    private String subject;
    private String body;
    private EmailType type;


}


