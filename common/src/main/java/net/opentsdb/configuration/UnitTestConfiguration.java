// This file is part of OpenTSDB.
// Copyright (C) 2018  The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.configuration;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import io.netty.util.HashedWheelTimer;
import net.opentsdb.configuration.provider.Provider;
import net.opentsdb.configuration.provider.ProviderFactory;
import net.opentsdb.configuration.provider.RuntimeOverrideProvider;

/**
 * A helper for use with Unit Testing Configuration consumers. The full
 * Configuration class is instantiated but only one or two providers are
 * given. So use this in a BeforeClass call.
 * <p>
 * To instantiate with default settings, use the {@link #getConfiguration(Map)}
 * by providing the reference to a map.
 * 
 * @since 3.0
 */
public class UnitTestConfiguration {

  /**
   * Helper function that returns a Configuration instance with a dead
   * timer and only the {@link RuntimeOverrideProvider} given.
   * 
   * @return A non-null config.
   */
  public static Configuration getConfiguration() {
    final String[] providers = new String[] {
        "--" + Configuration.CONFIG_PROVIDERS_KEY + "=RuntimeOverride"
    };
    return new Configuration(providers);
  }
  
  /**
   * Helper function that returns a Configuration instance with a dead
   * timer and only the {@link RuntimeOverrideProvider} given. The map
   * given must be a mutable reference.
   * 
   * @param settings A map of key values to load.
   * @return A non-null config.
   */
  public static Configuration getConfiguration(
      final Map<String, String> settings) {
    final String[] providers = new String[] {
        "--" + Configuration.CONFIG_PROVIDERS_KEY 
        + "=UnitTest,RuntimeOverride"
    };
    final Configuration config = new Configuration(providers);
    for (final Provider provider : config.providers()) {
      if (provider instanceof UnitTestProvider) {
        ((UnitTestProvider) provider).kvs = settings;
      }
    }
    return config;
  }

  public static class UnitTestProvider extends Provider {
    private Map<String, String> kvs;
    
    public UnitTestProvider(final ProviderFactory factory, 
                            final Configuration config,
                            final HashedWheelTimer timer, 
                            final Set<String> reload_keys) {
      super(factory, config, timer, reload_keys);
    }

    @Override
    public void close() throws IOException {
      // no-op
    }

    @Override
    public ConfigurationOverride getSetting(final String key) {
      if (kvs != null && kvs.containsKey(key)) {
        return ConfigurationOverride.newBuilder()
            .setSource(source())
            .setValue(kvs.get(key))
            .build();
      }
      return null;
    }

    @Override
    public String source() {
      return getClass().getSimpleName();
    }

    @Override
    public void reload() {
      // no-op
    }
    
  }
  
  public static class UnitTest implements ProviderFactory {

    @Override
    public void close() throws IOException {
      // no-op
    }

    @Override
    public Provider newInstance(final Configuration config, 
                                final HashedWheelTimer timer,
                                final Set<String> reload_keys) {
      return new UnitTestProvider(this, config, timer, reload_keys);
    }

    @Override
    public boolean isReloadable() {
      return false;
    }

    @Override
    public String description() {
      return "Only used for Unit Tests";
    }

    @Override
    public String simpleName() {
      return getClass().getSimpleName();
    }
    
  }
}