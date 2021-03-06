/*
 * Copyright 2018 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.config;

import com.thoughtworks.go.config.materials.MaterialConfigs;
import com.thoughtworks.go.config.materials.perforce.P4MaterialConfig;
import com.thoughtworks.go.config.materials.svn.SvnMaterialConfig;
import com.thoughtworks.go.config.materials.tfs.TfsMaterialConfig;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistrar;
import com.thoughtworks.go.config.registry.ConfigElementImplementationRegistry;
import com.thoughtworks.go.config.registry.NoPluginsInstalled;
import com.thoughtworks.go.security.CipherProvider;
import com.thoughtworks.go.security.GoCipher;
import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.TimeProvider;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class ConfigCipherUpdaterTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();
    private SystemEnvironment systemEnvironment = new SystemEnvironment();
    private ConfigCipherUpdater updater;
    private String timestamp;
    private ConfigCache configCache;
    private ConfigElementImplementationRegistry registry;
    private final String passwordEncryptedWithFlawedCipher = "ruRUF0mi2ia/BWpWMISbjQ==";
    private MagicalGoConfigXmlLoader magicalGoConfigXmlLoader;
    private final String password = "password";
    private File originalConfigFile;

    @Before
    public void setUp() throws Exception {
        systemEnvironment.setProperty(SystemEnvironment.CONFIG_DIR_PROPERTY, temporaryFolder.newFolder().getAbsolutePath());
        final Date currentTime = new DateTime().toDate();
        timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(currentTime);
        updater = new ConfigCipherUpdater(systemEnvironment, new TimeProvider() {
            @Override
            public Date currentTime() {
                return currentTime;
            }
        });
        configCache = new ConfigCache();
        registry = new ConfigElementImplementationRegistry(new NoPluginsInstalled());
        ConfigElementImplementationRegistrar registrar = new ConfigElementImplementationRegistrar(registry);
        registrar.initialize();
        magicalGoConfigXmlLoader = new MagicalGoConfigXmlLoader(configCache, registry);
        File configFileEncryptedWithFlawedCipher = new ClassPathResource("cruise-config-with-encrypted-with-flawed-cipher.xml").getFile();
        FileUtils.writeStringToFile(systemEnvironment.getCipherFile(), ConfigCipherUpdater.FLAWED_VALUE, UTF_8);
        ReflectionUtil.setStaticField(CipherProvider.class, "cachedKey", null);
        String xml = ConfigMigrator.migrate(FileUtils.readFileToString(configFileEncryptedWithFlawedCipher, UTF_8));
        originalConfigFile = new File(systemEnvironment.getCruiseConfigFile());
        FileUtils.writeStringToFile(originalConfigFile, xml, UTF_8);
    }

    @After
    public void tearDown() throws Exception {
        new SystemEnvironment().clearProperty(SystemEnvironment.CONFIG_DIR_PROPERTY);
    }

    @Test
    public void shouldNotMigrateAnythingIfCipherFileIsNotPresent_FreshInstalls() throws IOException {
        FileUtils.deleteQuietly(systemEnvironment.getCipherFile());
        updater.migrate();
        File cipherFile = systemEnvironment.getCipherFile();
        assertThat(cipherFile.exists(), is(false));
    }

    @Test
    public void shouldMigrateEncryptedPasswordsThatWereEncryptedWithFlawedCipher() throws Exception {
        String originalConfig = FileUtils.readFileToString(originalConfigFile, UTF_8);
        assertThat(originalConfig.contains("encryptedPassword=\"" + passwordEncryptedWithFlawedCipher + "\""), is(true));

        updater.migrate();
        File copyOfOldConfig = new File(systemEnvironment.getConfigDir(), "cipher.original." + timestamp);
        assertThat(copyOfOldConfig.exists(), is(true));
        assertThat(FileUtils.readFileToString(copyOfOldConfig, UTF_8).equals(ConfigCipherUpdater.FLAWED_VALUE), is(true));
        String newCipher = FileUtils.readFileToString(systemEnvironment.getCipherFile(), UTF_8);
        assertThat(newCipher.equals(ConfigCipherUpdater.FLAWED_VALUE), is(false));
        File editedConfigFile = new File(systemEnvironment.getCruiseConfigFile());
        String editedConfig = FileUtils.readFileToString(editedConfigFile, UTF_8);
        assertThat(editedConfig.contains("encryptedPassword=\"" + passwordEncryptedWithFlawedCipher + "\""), is(false));
        String passwordEncryptedWithNewCipher = new GoCipher().encrypt(password);
        CruiseConfig config = magicalGoConfigXmlLoader.loadConfigHolder(editedConfig).config;
        MaterialConfigs materialConfigs = config.getAllPipelineConfigs().get(0).materialConfigs();
        SvnMaterialConfig svnMaterial = materialConfigs.getSvnMaterial();
        assertThat(svnMaterial.getPassword(), is(password));
        assertThat(svnMaterial.getEncryptedPassword(), is(passwordEncryptedWithNewCipher));
        P4MaterialConfig p4Material = materialConfigs.getP4Material();
        assertThat(p4Material.getPassword(), is(password));
        assertThat(p4Material.getEncryptedPassword(), is(passwordEncryptedWithNewCipher));
        TfsMaterialConfig tfsMaterial = materialConfigs.getTfsMaterial();
        assertThat(tfsMaterial.getPassword(), is(password));
        assertThat(tfsMaterial.getEncryptedPassword(), is(passwordEncryptedWithNewCipher));
    }

    @Test
    public void shouldMigrateEncryptedManagerPasswordsEncryptedWithFlawedCipher() throws Exception {
        String originalConfig = FileUtils.readFileToString(originalConfigFile, UTF_8);
        assertThat(originalConfig.contains("encryptedPassword=\"" + passwordEncryptedWithFlawedCipher + "\""), is(true));

        updater.migrate();
        File copyOfOldConfig = new File(systemEnvironment.getConfigDir(), "cipher.original." + timestamp);
        assertThat(copyOfOldConfig.exists(), is(true));
        assertThat(FileUtils.readFileToString(copyOfOldConfig, UTF_8).equals(ConfigCipherUpdater.FLAWED_VALUE), is(true));
        assertThat(FileUtils.readFileToString(systemEnvironment.getCipherFile(), UTF_8).equals(ConfigCipherUpdater.FLAWED_VALUE), is(false));
        File editedConfigFile = new File(systemEnvironment.getCruiseConfigFile());
        String editedConfig = FileUtils.readFileToString(editedConfigFile, UTF_8);
        assertThat(editedConfig.contains("encryptedManagerPassword=\"" + passwordEncryptedWithFlawedCipher + "\""), is(false));
        CruiseConfig config = magicalGoConfigXmlLoader.loadConfigHolder(editedConfig).config;
        SecurityAuthConfig migratedLdapConfig = config.server().security().securityAuthConfigs().get(0);

        assertThat(migratedLdapConfig.getProperty("Password").getValue(), is(password));
        assertThat(migratedLdapConfig.getProperty("Password").getEncryptedValue(), is(new GoCipher().encrypt(password)));
    }

    @Test
    public void shouldMigrateEncryptedValuesEncryptedWithFlawedCipher() throws Exception {
        String originalConfig = FileUtils.readFileToString(originalConfigFile, UTF_8);
        assertThat(originalConfig.contains("<encryptedValue>" + passwordEncryptedWithFlawedCipher + "</encryptedValue>"), is(true));

        updater.migrate();
        File copyOfOldConfig = new File(systemEnvironment.getConfigDir(), "cipher.original." + timestamp);
        assertThat(copyOfOldConfig.exists(), is(true));
        assertThat(FileUtils.readFileToString(copyOfOldConfig, UTF_8).equals(ConfigCipherUpdater.FLAWED_VALUE), is(true));
        assertThat(FileUtils.readFileToString(systemEnvironment.getCipherFile(), UTF_8).equals(ConfigCipherUpdater.FLAWED_VALUE), is(false));
        File editedConfigFile = new File(systemEnvironment.getCruiseConfigFile());
        String editedConfig = FileUtils.readFileToString(editedConfigFile, UTF_8);
        assertThat(editedConfig.contains("<encryptedValue>" + passwordEncryptedWithFlawedCipher + "</encryptedValue>"), is(false));

        CruiseConfig config = magicalGoConfigXmlLoader.loadConfigHolder(editedConfig).config;
        EnvironmentVariablesConfig secureVariables = config.getAllPipelineConfigs().get(0).getSecureVariables();
        assertThat(secureVariables.first().getValue(), is(password));
        assertThat(secureVariables.first().getEncryptedValue(), is(new GoCipher().encrypt(password)));
    }

    @Test
    public void shouldNotTryMigratingOlderConfigsWhichWereNotEncryptedWithFlawedCipher() throws IOException, InvalidCipherTextException {
        String goodCipher = "269298bc31c44620";
        FileUtils.writeStringToFile(systemEnvironment.getCipherFile(), goodCipher, UTF_8);
        File originalConfigFile = new File(systemEnvironment.getCruiseConfigFile());
        FileUtils.copyFile(new ClassPathResource("cruise-config-with-encrypted-with-safe-cipher.xml").getFile(), originalConfigFile);
        String originalConfig = FileUtils.readFileToString(originalConfigFile, UTF_8);
        assertThat(originalConfig.contains("encryptedPassword=\"pVyuW5ny9I6YT4Ou+KLZhQ==\""), is(true));

        updater.migrate();
        File copyOfOldConfig = new File(systemEnvironment.getConfigDir(), "cipher.original." + timestamp);
        assertThat(copyOfOldConfig.exists(), is(false));
        assertThat(FileUtils.readFileToString(systemEnvironment.getCipherFile(), UTF_8).equals(goodCipher), is(true));
        File configFile = new File(systemEnvironment.getCruiseConfigFile());
        String editedConfig = FileUtils.readFileToString(configFile, UTF_8);
        assertThat(editedConfig.contains("encryptedPassword=\"pVyuW5ny9I6YT4Ou+KLZhQ==\""), is(true));
    }

}