import { existsSync, readFileSync } from 'fs';
import { dirname, join, resolve } from 'path';

import parseSamlangProjectConfiguration from './configuration-parser';
import { SamlangProjectConfiguration } from './configuration-type';

// Used for mock.
type ConfigurationLoader = {
  readonly startPath: string;
  readonly pathExistanceTester: (path: string) => boolean;
  readonly fileReader: (path: string) => string | null;
};

// eslint-disable-next-line camelcase
export const fileSystemLoader_EXPOSED_FOR_TESTING: ConfigurationLoader = {
  startPath: resolve('.'),
  pathExistanceTester: existsSync,
  fileReader: (path) => {
    try {
      return readFileSync(path).toString();
    } catch {
      return null;
    }
  },
};

type ConfigurationLoadingResult =
  | SamlangProjectConfiguration
  | 'UNREADABLE_CONFIGURATION_FILE'
  | 'UNPARSABLE_CONFIGURATION_FILE'
  | 'NO_CONFIGURATION';

const loadSamlangProjectConfiguration = ({
  startPath,
  pathExistanceTester,
  fileReader,
}: ConfigurationLoader = fileSystemLoader_EXPOSED_FOR_TESTING): ConfigurationLoadingResult => {
  let configurationDirectory = startPath;
  while (configurationDirectory !== '/') {
    const configurationPath = join(configurationDirectory, 'sconfig.json');
    if (pathExistanceTester(configurationPath)) {
      const content = fileReader(configurationPath);
      if (content == null) {
        return 'UNREADABLE_CONFIGURATION_FILE';
      }
      const configuration = parseSamlangProjectConfiguration(content);
      return configuration === null ? 'UNPARSABLE_CONFIGURATION_FILE' : configuration;
    }
    configurationDirectory = dirname(configurationDirectory);
  }
  return 'NO_CONFIGURATION';
};

export default loadSamlangProjectConfiguration;
