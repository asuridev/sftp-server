package com.asuridev.stcp.config;

import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.integration.file.remote.session.CachingSessionFactory;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;

@Configuration
@EnableConfigurationProperties(SftpProperties.class)
public class SftpConfig {

    @Bean
    public SessionFactory<SftpClient.DirEntry> sftpSessionFactory(SftpProperties properties) {
        DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory();
        factory.setHost(properties.host());
        factory.setPort(properties.port());
        factory.setUser(properties.user());
        factory.setPassword(properties.password());
        // Solo en local: el contenedor emulado cambia de host key cada vez que se
        // recrea. Contra el STCP Gemini real esto debe ir en false y la host key
        // real debe instalarse aparte (ver docs/STCP-GEMINI.md, sección 9).
        factory.setAllowUnknownKeys(properties.allowUnknownKeys());
        // Pool de sesiones: cada subida toma y devuelve una sesión en vez de abrir
        // una conexión SSH nueva por request.
        return new CachingSessionFactory<>(factory);
    }

    @Bean
    public SftpRemoteFileTemplate sftpRemoteFileTemplate(SessionFactory<SftpClient.DirEntry> sftpSessionFactory,
            SftpProperties properties) {
        SftpRemoteFileTemplate template = new SftpRemoteFileTemplate(sftpSessionFactory);
        template.setRemoteDirectoryExpression(new LiteralExpression(properties.remoteDirectory()));
        template.setAutoCreateDirectory(true);
        return template;
    }
}
