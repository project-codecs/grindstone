# Welcome!
Wowzers! You generated an IDEA project using Project Codex's Grindstone! But what to do now?

- If you use publishing, go to build.gradle.kts and find the TODO comment, then configure what it tells you to. <br>
  You should also go to gradle.properties and change your modrinth slug. Publishing requires setting the MODRINTH_TOKEN
  property when running the "publishModrinth" task.
- Verify the gradle.properties files in all the versions/ folders. Due to misconfigurations it is possible for them to contain
  invalid entries marked as "CHANGE-ME".
- You should also go and give all the repositories defined by strictMaven in the repositories block names.
- If you want, go edit the 'authors' and 'description' fields in fabric.mod.json as they are not supported (I'm just lazy :3)