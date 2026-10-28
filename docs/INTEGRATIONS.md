# Driving isx from other tools

Tools that run coding agents in parallel, such as Orca and herdr, can give each agent an isx instance of its own.
This page shows how to connect them, and what isx needs from any other tool.

## How a tool connects

Which way a tool connects depends on what it can do, not on a choice you make.

| The tool | What isx does | Example |
|----------|---------------|---------|
| Runs a command to create a machine and another to destroy it | The tool drives isx through ordinary commands | Orca |
| Only keeps a list of SSH machines | isx adds each instance to the tool's list, and removes it again | herdr |

Either way, you put the tool's isx tool in a template, build it, and the rest follows.
Agents still get no credentials in their instance: the proxy on your host adds them, as for any branch.

## herdr

[herdr](https://herdr.dev) runs agents in terminal workspaces, on your machine or on SSH machines it lists.

1. Install herdr on your host.
2. Add the `herdr` tool to a template and build it:

   ```yaml
   name: tpl-herdr
   parent: tpl-minimal
   tools:
     - herdr
   ```

3. Branch it: `isx branch agent-1 --from tpl-herdr`.

isx adds `agent-1` to herdr as a machine on its first start, and herdr starts its server there.
`herdr --machine agent-1 agent list` then reaches it.
When you destroy the instance, isx removes the machine from herdr.
A machine you added to herdr yourself is never removed by isx.

## Orca

[Orca](https://github.com/stablyai/orca) can run each workspace on a machine of its own, through an environment recipe.
A recipe is four commands: one creates the machine, and three suspend, resume and destroy it.
This is a first version: setting it up takes a few steps, which later releases will remove.

1. Build a template with the `orca` tool, which Orca's server needs, and with your repository cloned as its workdir:

   ```yaml
   name: tpl-orca
   parent: tpl-java
   tools:
     - orca
   repos:
     - url: https://github.com/you/your-project.git
       path: ~/your-project
   ```

2. Let Orca destroy the instances it creates, in `~/.config/incus-spawn/config.yaml`.
   The recipe also uses this to take back an instance a failed create left:

   ```yaml
   drivers:
     orca:
       destroy: true
   ```

3. Download the recipe script for your isx version onto your `PATH`.
   It needs `bash`, `jq`, and `sha256sum` or `shasum`:

   ```shell
   curl -fsSLo ~/.local/bin/isx-orca-recipe https://raw.githubusercontent.com/Sanne/incus-spawn/v<version>/integrations/orca/isx-orca-recipe
   chmod +x ~/.local/bin/isx-orca-recipe
   ```

4. Add a recipe to your repository's `orca.yaml`, naming the template:

   ```yaml
   environmentRecipes:
     - id: isx
       name: isx sandbox
       create: isx-orca-recipe create --from tpl-orca
       suspend: isx-orca-recipe suspend
       resume: isx-orca-recipe resume
       destroy: isx-orca-recipe destroy
   ```

Each workspace then gets an instance named after Orca's runtime, such as `orca-3f2a6c1e0b5d`.
Orca reaches it over SSH, adds its worktree under the template's workdir, and runs its agents there.
The recipe supports Orca's worktree mode only, in which Orca makes the worktree itself.

## Any other tool

A tool with commands to create and destroy a machine can use isx the same way as the Orca recipe does:

| Step | Command |
|------|---------|
| Create | `isx branch <name> --from <template> --owner <tool> --owner-id <its id> --format=json` |
| Suspend | `isx stop <name>` |
| Resume | `isx start <name>` |
| Destroy | `isx destroy <name> --owner <tool> --owner-id <its id>` |

`isx branch --format=json` prints how to reach the instance over SSH, including a `ProxyCommand` that starts a stopped instance.
The [CLI reference](CLI.md#isx-branch-fields) lists its fields.
`isx list` shows which tool owns each instance, and the tool can only destroy its own, once `drivers:` lets it.
A tool that only knows SSH machines can reach any instance whose template installs `sshd`, as `ssh <name>`.
